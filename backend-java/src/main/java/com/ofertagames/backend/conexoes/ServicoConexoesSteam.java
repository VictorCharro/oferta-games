package com.ofertagames.backend.conexoes;

import com.ofertagames.backend.atividadesperfil.RepositorioAtividadesPerfil;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Service
public class ServicoConexoesSteam {
  private static final String URL_OPENID = "https://steamcommunity.com/openid/login";
  private static final String IDENTIFICADOR_SELECT = "http://specs.openid.net/auth/2.0/identifier_select";
  private static final Pattern STEAM_ID = Pattern.compile("^https?://steamcommunity\\.com/openid/id/(\\d+)/?$");

  private final RepositorioConexoesSteam conexoes;
  private final ClienteSteamWeb steam;
  private final RepositorioAtividadesPerfil atividades;
  private final RestClient restClient;
  private final String urlFrontend;
  private final String urlBackend;

  ServicoConexoesSteam(
      RepositorioConexoesSteam conexoes,
      ClienteSteamWeb steam,
      RepositorioAtividadesPerfil atividades,
      RestClient.Builder restClientBuilder,
      @Value("${app.steam.frontend-url}") String urlFrontend,
      @Value("${app.public-backend-url:}") String urlBackend
  ) {
    this.conexoes = conexoes;
    this.steam = steam;
    this.atividades = atividades;
    this.restClient = restClientBuilder.build();
    this.urlFrontend = removerBarraFinal(urlFrontend);
    this.urlBackend = removerBarraFinal(urlBackend);
  }

  String iniciar(String usuarioId) {
    if (urlBackend.isBlank()) throw new UrlBackendNaoConfiguradaException();
    UUID estado = UUID.randomUUID();
    conexoes.criarEstado(estado, usuarioId);
    String retorno = urlBackend + "/api/conexoes/steam/retorno?state=" + estado;
    return URL_OPENID + "?"
        + parametro("openid.ns", "http://specs.openid.net/auth/2.0") + "&"
        + parametro("openid.mode", "checkid_setup") + "&"
        + parametro("openid.return_to", retorno) + "&"
        + parametro("openid.realm", urlBackend) + "&"
        + parametro("openid.identity", IDENTIFICADOR_SELECT) + "&"
        + parametro("openid.claimed_id", IDENTIFICADOR_SELECT);
  }

  Optional<String> concluir(UUID estado, MultiValueMap<String, String> parametros) {
    Optional<String> usuarioId = conexoes.consumirEstado(estado);
    if (usuarioId.isEmpty() || !validarRespostaOpenId(parametros)) return Optional.empty();
    String steamId = extrairSteamId(parametros.getFirst("openid.claimed_id"));
    if (steamId == null) return Optional.empty();

    ClienteSteamWeb.PerfilSteam perfil = buscarPerfilSemBloquearConexao(steamId);
    conexoes.salvarConexao(usuarioId.get(), steamId, perfil.nome(), perfil.avatarUrl());
    atividades.registrar(usuarioId.get(), "STEAM_CONECTADA");
    return usuarioId;
  }

  void sincronizarBiblioteca(String usuarioId) {
    RepositorioConexoesSteam.ConexaoSteam conexao = conexoes.buscarConexao(usuarioId)
        .orElseThrow(ConexaoSteamNaoEncontradaException::new);
    try {
      conexoes.substituirBiblioteca(usuarioId, steam.buscarBiblioteca(conexao.steamId()));
      atividades.registrar(usuarioId, "BIBLIOTECA_STEAM_SINCRONIZADA");
    } catch (RuntimeException erro) {
      conexoes.registrarErro(usuarioId, mensagemErro(erro));
      throw erro;
    }
  }

  int sincronizarConquistas(int limitePorUsuario) {
    if (!steam.configurada()) return 0;
    int atualizados = 0;
    for (RepositorioConexoesSteam.ConexaoUsuarioSteam conexao : conexoes.listarConexoes()) {
      for (RepositorioConexoesSteam.JogoBibliotecaSteam jogo : conexoes.listarParaConquistas(conexao.usuarioId(), limitePorUsuario)) {
        ClienteSteamWeb.ConquistasSteam conquistas = steam.buscarConquistas(conexao.steamId(), jogo.appId());
        if (conquistas == null) continue;
        conexoes.salvarConquistas(conexao.usuarioId(), jogo.appId(), conquistas.desbloqueadas(), conquistas.total());
        atualizados++;
      }
      conexoes.marcarConquistasSincronizadas(conexao.usuarioId());
    }
    return atualizados;
  }

  int sincronizarConquistasDoUsuario(String usuarioId, int limite) {
    if (!steam.configurada()) return 0;
    RepositorioConexoesSteam.ConexaoSteam conexao = conexoes.buscarConexao(usuarioId)
        .orElseThrow(ConexaoSteamNaoEncontradaException::new);
    int atualizados = 0;
    for (RepositorioConexoesSteam.JogoBibliotecaSteam jogo : conexoes.listarParaConquistas(usuarioId, limite)) {
      ClienteSteamWeb.ConquistasSteam conquistas = steam.buscarConquistas(conexao.steamId(), jogo.appId());
      if (conquistas == null) continue;
      conexoes.salvarConquistas(usuarioId, jogo.appId(), conquistas.desbloqueadas(), conquistas.total());
      atualizados++;
    }
    conexoes.marcarConquistasSincronizadas(usuarioId);
    atividades.registrar(usuarioId, "CONQUISTAS_STEAM_SINCRONIZADAS");
    return atualizados;
  }

  public java.util.List<JogoBibliotecaSteam> biblioteca(String usuarioId) {
    return conexoes.listarBiblioteca(usuarioId, 100).stream()
        .map(jogo -> new JogoBibliotecaSteam(jogo.appId(), jogo.titulo(), jogo.minutosJogadas(), jogo.iconeHash()))
        .toList();
  }

  public StatusConexaoSteam status(String usuarioId) {
    Optional<RepositorioConexoesSteam.ConexaoSteam> conexao = conexoes.buscarConexao(usuarioId);
    if (conexao.isEmpty()) return StatusConexaoSteam.desconectada();
    RepositorioConexoesSteam.ResumoSteam resumo = conexoes.resumir(usuarioId);
    return new StatusConexaoSteam(
        true,
        conexao.get().nome(),
        conexao.get().avatarUrl(),
        conexao.get().bibliotecaSincronizadaEm(),
        conexao.get().conquistasSincronizadasEm(),
        conexao.get().ultimoErro(),
        resumo.totalJogos(),
        resumo.totalMinutos(),
        resumo.conquistasDesbloqueadas(),
        resumo.conquistasTotal());
  }

  void remover(String usuarioId) {
    conexoes.removerConexao(usuarioId);
  }

  String urlRetornoSucesso() {
    return urlFrontend + "/configuracoes?steam=connected";
  }

  String urlRetornoErro() {
    return urlFrontend + "/configuracoes?steam=error";
  }

  private boolean validarRespostaOpenId(MultiValueMap<String, String> parametros) {
    if (!"id_res".equals(parametros.getFirst("openid.mode"))) return false;
    MultiValueMap<String, String> formulario = new LinkedMultiValueMap<>();
    parametros.forEach((chave, valores) -> {
      if (chave.startsWith("openid.")) formulario.put(chave, valores);
    });
    formulario.set("openid.mode", "check_authentication");
    try {
      String resposta = restClient.post()
          .uri(URL_OPENID)
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(formulario)
          .retrieve()
          .body(String.class);
      return resposta != null && resposta.contains("is_valid:true");
    } catch (RuntimeException erro) {
      return false;
    }
  }

  private static String extrairSteamId(String claimedId) {
    if (claimedId == null) return null;
    Matcher matcher = STEAM_ID.matcher(claimedId);
    return matcher.matches() ? matcher.group(1) : null;
  }

  private static String parametro(String nome, String valor) {
    return URLEncoder.encode(nome, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(valor, StandardCharsets.UTF_8);
  }

  private static String removerBarraFinal(String url) {
    if (url == null) return "";
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  private static String mensagemErro(RuntimeException erro) {
    return erro instanceof ClienteSteamWeb.BibliotecaSteamPrivadaException
        ? "A biblioteca Steam precisa estar publica para ser sincronizada."
        : "Nao foi possivel sincronizar os dados da Steam.";
  }

  private ClienteSteamWeb.PerfilSteam buscarPerfilSemBloquearConexao(String steamId) {
    if (!steam.configurada()) return new ClienteSteamWeb.PerfilSteam(null, null);
    try {
      return steam.buscarPerfil(steamId);
    } catch (RuntimeException erro) {
      return new ClienteSteamWeb.PerfilSteam(null, null);
    }
  }

  public record StatusConexaoSteam(
      boolean conectada,
      String nome,
      String avatarUrl,
      String bibliotecaSincronizadaEm,
      String conquistasSincronizadasEm,
      String ultimoErro,
      long totalJogos,
      long totalMinutos,
      long conquistasDesbloqueadas,
      long conquistasTotal
  ) {
    static StatusConexaoSteam desconectada() {
      return new StatusConexaoSteam(false, null, null, null, null, null, 0, 0, 0, 0);
    }
  }

  public record JogoBibliotecaSteam(int appId, String titulo, int minutosJogadas, String iconeHash) {}

  static class ConexaoSteamNaoEncontradaException extends RuntimeException {}
  static class UrlBackendNaoConfiguradaException extends RuntimeException {}
}
