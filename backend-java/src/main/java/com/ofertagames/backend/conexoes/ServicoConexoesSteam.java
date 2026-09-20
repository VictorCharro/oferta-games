package com.ofertagames.backend.conexoes;

import com.ofertagames.backend.atividadesperfil.RepositorioAtividadesPerfil;
import com.ofertagames.backend.colecoesperfil.RepositorioColecoesPerfil;
import com.ofertagames.backend.jogos.RepositorioJogos;
import com.ofertagames.backend.jogos.ServicoConquistasSobDemanda;
import com.ofertagames.backend.steam.DetalhesAplicativoSteam;
import com.ofertagames.backend.steam.ServicoSteam;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Conexao da conta Steam via OpenID 2.0 e sincronizacao de biblioteca, horas e conquistas.
 *
 * <p>A Steam nao usa OAuth: o fluxo e OpenID, em que a correlacao com o usuario nosso e feita por
 * um {@code state} sorteado e guardado no banco antes do redirect. E a diferenca central em
 * relacao ao {@link ServicoConexoesXbox}, que por ser OAuth com bearer nao precisa dessa tabela.
 *
 * <p><b>Sincronizacao e upsert-only</b>, igual a do Xbox: {@code steam_library_games} nunca sofre
 * DELETE. Nem sempre foi assim — em 06/08/2026 o resync apagava e reinseria a biblioteca, e o
 * DELETE em cascata levou junto favoritos e colecoes dos usuarios. Desde a correcao o caminho e so
 * INSERT/UPDATE por {@code (user_id, app_id)}.
 *
 * <p>Nao reintroduzir DELETE aqui: qualquer dado do usuario que referencie a biblioteca
 * (favoritos, colecoes, ordem de platinados) volta a correr o risco de sumir em cascata.
 *
 * <p>Nunca lanca excecao por falha da API da Steam durante a sincronizacao: registra o erro em
 * {@code steam_connections.last_error} e mantem a conexao viva, pra uma instabilidade momentanea
 * nao desconectar a conta do usuario.
 */
@Service
public class ServicoConexoesSteam {
  private static final Logger log = LoggerFactory.getLogger(ServicoConexoesSteam.class);
  private static final String URL_OPENID = "https://steamcommunity.com/openid/login";
  private static final String IDENTIFICADOR_SELECT = "http://specs.openid.net/auth/2.0/identifier_select";
  private static final Pattern STEAM_ID = Pattern.compile("^https?://steamcommunity\\.com/openid/id/(\\d+)/?$");

  private static final String NOME_COLECAO_WISHLIST = "Lista de Desejos (Steam)";
  private static final String ORIGEM_COLECAO_WISHLIST = "steam_wishlist";

  private final RepositorioConexoesSteam conexoes;
  private final ClienteSteamWeb steam;
  private final ServicoSteam steamLoja;
  private final RepositorioAtividadesPerfil atividades;
  private final RepositorioColecoesPerfil colecoes;
  private final RepositorioJogos jogos;
  private final ServicoConquistasSobDemanda conquistasSobDemanda;
  private final RestClient restClient;
  private final String urlFrontend;
  private final String urlBackend;

  ServicoConexoesSteam(
      RepositorioConexoesSteam conexoes,
      ClienteSteamWeb steam,
      ServicoSteam steamLoja,
      RepositorioAtividadesPerfil atividades,
      RepositorioColecoesPerfil colecoes,
      RepositorioJogos jogos,
      ServicoConquistasSobDemanda conquistasSobDemanda,
      RestClient.Builder restClientBuilder,
      @Value("${app.steam.frontend-url}") String urlFrontend,
      @Value("${app.public-backend-url:}") String urlBackend
  ) {
    this.conexoes = conexoes;
    this.steam = steam;
    this.steamLoja = steamLoja;
    this.atividades = atividades;
    this.colecoes = colecoes;
    this.jogos = jogos;
    this.conquistasSobDemanda = conquistasSobDemanda;
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
    if (usuarioId.isEmpty() || !validarRespostaOpenId(estado, parametros)) return Optional.empty();
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
      boolean primeiraSincronizacao = !conexoes.atividadesBibliotecaInicializadas(usuarioId);
      java.util.List<RepositorioConexoesSteam.JogoBibliotecaSteam> jogos = steam.buscarBiblioteca(conexao.steamId());
      java.util.List<RepositorioConexoesSteam.JogoBibliotecaSteam> novos = conexoes.salvarBiblioteca(usuarioId, jogos, !primeiraSincronizacao);
      if (primeiraSincronizacao) {
        atividades.registrar(usuarioId, "BIBLIOTECA_STEAM_SINCRONIZADA", jogos.size() + " jogos");
        conexoes.marcarAtividadesBibliotecaInicializadas(usuarioId);
      } else {
        for (RepositorioConexoesSteam.JogoBibliotecaSteam jogo : novos) {
          atividades.registrar(usuarioId, "JOGO_ADICIONADO_BIBLIOTECA_STEAM", jogo.titulo());
        }
      }
    } catch (RuntimeException erro) {
      conexoes.registrarErro(usuarioId, mensagemErro(erro));
      throw erro;
    }
    // Falha na wishlist nunca derruba a sincronizacao da biblioteca (que ja teve sucesso acima) -
    // erro isolado, so logado em steam_connections.last_error.
    try {
      sincronizarWishlist(usuarioId, conexao.steamId());
    } catch (RuntimeException erro) {
      conexoes.registrarErro(usuarioId, "Wishlist: " + mensagemErro(erro));
    }
  }

  /**
   * Cria (se ainda nao existir) e atualiza a colecao automatica "Lista de Desejos (Steam)" -
   * reflete a wishlist real, travada pra edicao manual (ver {@code origem} em
   * {@code profile_collections}). So entram jogos ja descobertos no nosso catalogo (casados por
   * {@code steam_app_id}); os demais ficam de fora ate serem descobertos, sem erro.
   *
   * <p>Roda dentro de {@link #sincronizarBiblioteca} (mesma cadencia: conexao inicial, botao
   * manual e solicitacao publica) — nao existe um job agendado separado so pra wishlist.
   */
  private void sincronizarWishlist(String usuarioId, String steamId) {
    if (!steam.configurada()) return;
    java.util.List<Integer> appIdsNaWishlist = steam.buscarWishlist(steamId);
    java.util.Map<Integer, Long> idsPorAppId = jogos.buscarIdsPorSteamAppIds(appIdsNaWishlist);
    java.util.List<Long> gameIdsNaOrdem = appIdsNaWishlist.stream()
        .map(idsPorAppId::get)
        .filter(java.util.Objects::nonNull)
        .toList();
    long colecaoId = colecoes.buscarOuCriarColecaoSistema(usuarioId, ORIGEM_COLECAO_WISHLIST, NOME_COLECAO_WISHLIST);
    colecoes.sincronizarItensSistema(colecaoId, usuarioId, gameIdsNaOrdem);
  }

  int sincronizarConquistas(int limitePorUsuario) {
    if (!steam.configurada()) return 0;
    int atualizados = 0;
    for (RepositorioConexoesSteam.ConexaoUsuarioSteam conexao : conexoes.listarConexoes()) {
      atualizados += sincronizarConquistasDaConta(conexao.usuarioId(), conexao.steamId(), limitePorUsuario);
    }
    return atualizados;
  }

  int sincronizarConquistasDoUsuario(String usuarioId, int limite) {
    if (!steam.configurada()) return 0;
    RepositorioConexoesSteam.ConexaoSteam conexao = conexoes.buscarConexao(usuarioId)
        .orElseThrow(ConexaoSteamNaoEncontradaException::new);
    return sincronizarConquistasDaConta(usuarioId, conexao.steamId(), limite);
  }

  public void sincronizarPerfilCompleto(String usuarioId) {
    sincronizarBiblioteca(usuarioId);
    sincronizarConquistasDoUsuario(usuarioId, 50);
  }

  public boolean reservarAtualizacaoPublica(String usuarioId) {
    return conexoes.reservarAtualizacaoPublica(usuarioId);
  }

  // Limite padrao do perfil publico: segura o tamanho da resposta.
  public java.util.List<JogoBibliotecaSteam> biblioteca(String usuarioId) {
    return biblioteca(usuarioId, 100);
  }

  // Usado pelo endpoint autenticado do dono, onde a biblioteca inteira e necessaria.
  public java.util.List<JogoBibliotecaSteam> biblioteca(String usuarioId, int limite) {
    return conexoes.listarBiblioteca(usuarioId, limite).stream()
        .map(jogo -> new JogoBibliotecaSteam(jogo.appId(), jogo.titulo(), jogo.minutosJogadas(), jogo.iconeHash(), jogo.conquistasDesbloqueadas(), jogo.conquistasTotal(), jogo.capaUrl(), null, jogo.platinumPosition()))
        .toList();
  }

  public void reordenarPlatinados(String usuarioId, java.util.List<Integer> appIds) {
    conexoes.reordenarPlatinados(usuarioId, appIds);
  }

  /**
   * Resolve e grava a capa real dos jogos da biblioteca, um lote por vez.
   *
   * <p>Precisa consultar a API porque o padrao antigo de URL
   * ({@code cdn.akamai.steamstatic.com/steam/apps/{appId}/header.jpg}) deixou de valer: as imagens
   * de jogos recentes vivem num caminho com hash imprevisivel, e so a Steam sabe a URL certa.
   * Montar a URL na mao gera imagem quebrada.
   */
  public int preencherCapasBiblioteca(int limite) {
    int atualizadas = 0;
    for (RepositorioConexoesSteam.JogoParaCapa jogo : conexoes.listarSemCapa(limite)) {
      String capa = steamLoja.buscarDetalhesAplicativo(String.valueOf(jogo.appId()))
          .map(DetalhesAplicativoSteam::imagemCabecalho)
          .orElse(null);
      conexoes.salvarCapa(jogo.usuarioId(), jogo.appId(), capa);
      atualizadas++;
    }
    return atualizadas;
  }

  /**
   * Conquistas que o usuario ja desbloqueou num jogo, com a data de cada uma.
   *
   * <p>Existe como metodo publico especificamente porque o repositorio de {@code conexoes} e
   * package-private: e a unica porta pela qual o pacote {@code jogos} cruza o progresso pessoal com
   * o catalogo global de conquistas na pagina do jogo.
   *
   * @return mapa vazio (nunca excecao) quando o usuario nao tem Steam conectada, nunca jogou esse
   *     app id, ou nao esta logado — a pagina do jogo entao mostra tudo bloqueado e 0%
   */
  /**
   * Ultimas conquistas desbloqueadas, ja com nome oficial e icone vindos do catalogo.
   *
   * <p>Cruza os dois bancos: o progresso do usuario ({@code steam_user_achievements}) esta no
   * Supabase e so guarda {@code api_name}; nome em pt-BR e icone estao em
   * {@code game_achievements}, no Postgres do catalogo. Conquista cujo jogo ainda nao foi
   * detalhado no catalogo entra mesmo assim, com o nome derivado do {@code api_name} e sem icone
   * — melhor um item sem imagem do que a lista ficar com buraco.
   *
   * @return lista vazia (nunca excecao) quando nao ha Steam conectada ou nenhuma conquista com
   *     data registrada
   */
  public java.util.List<ConquistaRecenteSteam> conquistasRecentes(String usuarioId, int limite) {
    java.util.List<RepositorioConexoesSteam.ConquistaRecente> recentes = conexoes.listarConquistasRecentes(usuarioId, limite);
    if (recentes.isEmpty()) return java.util.List.of();

    java.util.Map<String, RepositorioJogos.ConquistaDoCatalogo> doCatalogo =
        jogos.buscarConquistasDoCatalogo(
            recentes.stream().map(RepositorioConexoesSteam.ConquistaRecente::appId).distinct().toList(),
            recentes.stream().map(RepositorioConexoesSteam.ConquistaRecente::apiName).distinct().toList());

    // Conquista sem linha no catalogo = catalogo velho pra esse jogo (tipico de live-service, que
    // adiciona conquista depois da nossa coleta). Refaz o jogo inteiro em background, igual ao
    // botao de admin "Preencher tudo agora" - ver ServicoConquistasSobDemanda, que cuida da trava
    // de concorrencia e do intervalo minimo. Esta leitura nao espera nada disso.
    java.util.List<Integer> semCatalogo = recentes.stream()
        .filter(conquista -> !doCatalogo.containsKey(conquista.appId() + "|" + conquista.apiName()))
        .map(RepositorioConexoesSteam.ConquistaRecente::appId)
        .distinct()
        .toList();
    conquistasSobDemanda.agendarPreenchimentoCompleto(semCatalogo);

    return recentes.stream()
        .map(conquista -> {
          RepositorioJogos.ConquistaDoCatalogo catalogo = doCatalogo.get(conquista.appId() + "|" + conquista.apiName());
          String nome = catalogo != null && catalogo.nome() != null && !catalogo.nome().isBlank() ? catalogo.nome() : conquista.titulo();
          return new ConquistaRecenteSteam(
              conquista.appId(), nome, conquista.tituloJogo(),
              catalogo == null ? null : catalogo.iconeUrl(), conquista.desbloqueadaEm());
        })
        .toList();
  }

  public java.util.Map<String, java.time.Instant> conquistasDesbloqueadas(String usuarioId, int appId) {
    return conexoes.conquistasDesbloqueadasComData(usuarioId, appId);
  }

  private int sincronizarConquistasDaConta(String usuarioId, String steamId, int limite) {
    boolean primeiraSincronizacao = !conexoes.atividadesConquistasInicializadas(usuarioId);
    boolean resumoInicialRegistrado = conexoes.atividadesConquistasIniciadas(usuarioId);
    int atualizados = 0;
    for (RepositorioConexoesSteam.JogoBibliotecaSteam jogo : conexoes.listarParaConquistas(usuarioId, limite)) {
      ClienteSteamWeb.ConquistasSteam conquistas = steam.buscarConquistas(steamId, jogo.appId());
      if (conquistas == null) {
        if (primeiraSincronizacao) {
          conexoes.salvarConquistas(usuarioId, jogo.appId(), 0, 0);
          atualizados++;
        }
        continue;
      }
      java.util.Set<String> conhecidas = primeiraSincronizacao ? java.util.Set.of() : conexoes.conquistasDesbloqueadas(usuarioId, jogo.appId());
      conexoes.salvarConquistas(usuarioId, jogo.appId(), conquistas.desbloqueadas().size(), conquistas.total());
      conexoes.salvarConquistasDetalhadas(usuarioId, jogo.appId(), conquistas.desbloqueadas());
      if (!primeiraSincronizacao) {
        for (ClienteSteamWeb.ConquistaSteam conquista : conquistas.desbloqueadas()) {
          if (!conhecidas.contains(conquista.identificador())) {
            atividades.registrar(usuarioId, "CONQUISTA_STEAM_DESBLOQUEADA", jogo.titulo() + " - " + conquista.titulo());
          }
        }
      }
      atualizados++;
    }
    conexoes.marcarConquistasSincronizadas(usuarioId);
    if (primeiraSincronizacao && !resumoInicialRegistrado) {
      atividades.registrar(usuarioId, "CONQUISTAS_STEAM_SINCRONIZADAS", atualizados + " jogos analisados");
      conexoes.marcarAtividadesConquistasIniciadas(usuarioId);
    }
    if (primeiraSincronizacao && !conexoes.existemJogosSemConquistas(usuarioId)) {
      conexoes.marcarAtividadesConquistasInicializadas(usuarioId);
    }
    return atualizados;
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
        resumo.conquistasTotal(),
        resumo.jogosPlatinados());
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

  /**
   * Alem da assinatura ({@code check_authentication} na propria Steam), confere os campos que a
   * especificacao OpenID 2.0 manda o site conferir (issue #30):
   * <ul>
   *   <li>{@code op_endpoint} e a Steam — uma asserção de outro provedor nao vale aqui;</li>
   *   <li>{@code return_to} e exatamente a URL de retorno DESTE login, com o mesmo {@code state}:
   *       impede reaproveitar uma asserção valida emitida pra outro site ou outra tentativa;</li>
   *   <li>{@code claimed_id} e {@code identity} sao o mesmo SteamID.</li>
   * </ul>
   */
  static boolean camposOpenIdConferem(String urlBackend, UUID estado, MultiValueMap<String, String> parametros) {
    String retornoEsperado = urlBackend + "/api/conexoes/steam/retorno?state=" + estado;
    return URL_OPENID.equals(parametros.getFirst("openid.op_endpoint"))
        && retornoEsperado.equals(parametros.getFirst("openid.return_to"))
        && parametros.getFirst("openid.claimed_id") != null
        && parametros.getFirst("openid.claimed_id").equals(parametros.getFirst("openid.identity"));
  }

  private boolean validarRespostaOpenId(UUID estado, MultiValueMap<String, String> parametros) {
    if (!"id_res".equals(parametros.getFirst("openid.mode"))) return false;
    if (!camposOpenIdConferem(urlBackend, estado, parametros)) return false;
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
      long conquistasTotal,
      long jogosPlatinados
  ) {
    static StatusConexaoSteam desconectada() {
      return new StatusConexaoSteam(false, null, null, null, null, null, 0, 0, 0, 0, 0);
    }
  }

  // Tipo usado tambem pra biblioteca Xbox (campo "plataforma"), pra nao duplicar toda a
  // renderizacao/agregacao de biblioteca no frontend - so o construtor de 9 args (Steam) e
  // mantido com "steam" implicito, pra nao precisar mexer nos outros pontos que ja o usam.
  public record JogoBibliotecaSteam(int appId, String titulo, Integer minutosJogadas, String iconeHash, Integer conquistasDesbloqueadas, Integer conquistasTotal, String capaUrl, String catalogSlug, Integer platinumPosition, String plataforma) {
    public JogoBibliotecaSteam(int appId, String titulo, int minutosJogadas, String iconeHash, int conquistasDesbloqueadas, int conquistasTotal, String capaUrl, String catalogSlug, Integer platinumPosition) {
      this(appId, titulo, minutosJogadas, iconeHash, conquistasDesbloqueadas, conquistasTotal, capaUrl, catalogSlug, platinumPosition, "steam");
    }

    public JogoBibliotecaSteam comCatalogSlug(String catalogSlug) {
      return new JogoBibliotecaSteam(appId, titulo, minutosJogadas, iconeHash, conquistasDesbloqueadas, conquistasTotal, capaUrl, catalogSlug, platinumPosition, plataforma);
    }
  }

  /**
   * DTO publico porque {@link RepositorioConexoesSteam} e package-private: e assim que o pacote
   * {@code perfis} enxerga essas conquistas (mesmo motivo de {@link JogoBibliotecaSteam}).
   *
   * <p>{@code iconeUrl} pode ser {@code null} quando o jogo ainda nao tem conquistas detalhadas no
   * catalogo — o frontend cai num placeholder nesse caso.
   */
  public record ConquistaRecenteSteam(int appId, String titulo, String tituloJogo, String iconeUrl, java.time.Instant desbloqueadaEm) {}

  static class ConexaoSteamNaoEncontradaException extends RuntimeException {}
  static class UrlBackendNaoConfiguradaException extends RuntimeException {}
}
