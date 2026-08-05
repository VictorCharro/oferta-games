package com.ofertagames.backend.conexoes;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class ServicoConexoesXbox {
  private final RepositorioConexoesXbox conexoes;
  private final ClienteXbl xbl;

  ServicoConexoesXbox(RepositorioConexoesXbox conexoes, ClienteXbl xbl) {
    this.conexoes = conexoes;
    this.xbl = xbl;
  }

  boolean configurada() {
    return xbl.configurada();
  }

  String appKey() {
    if (!xbl.configurada()) throw new XblNaoConfiguradoException();
    return xbl.appKey();
  }

  void concluir(String usuarioId, String code) {
    ClienteXbl.RespostaClaimXbl resposta = xbl.trocarCodigo(code);
    if (resposta == null || resposta.xuid() == null) {
      throw new FalhaAoConectarXboxException(resposta == null ? null : resposta.message());
    }
    conexoes.salvarConexao(usuarioId, resposta.xuid(), resposta.gamertag(), resposta.avatar(), resposta.gamerscore(), resposta.tokenUsuario());
  }

  public StatusConexaoXbox status(String usuarioId) {
    Optional<RepositorioConexoesXbox.ConexaoXbox> conexao = conexoes.buscarConexao(usuarioId);
    if (conexao.isEmpty()) return StatusConexaoXbox.desconectada();
    return new StatusConexaoXbox(
        true,
        conexao.get().gamertag(),
        conexao.get().avatarUrl(),
        conexao.get().gamerscore(),
        conexao.get().ultimoErro());
  }

  void remover(String usuarioId) {
    conexoes.removerConexao(usuarioId);
  }

  // So incrementa/atualiza (upsert por titleId) - nunca apaga jogos ja salvos, mesmo que a
  // OpenXBL pare de devolve-los (ex: usuario escondeu um jogo do historico dele).
  void sincronizarBiblioteca(String usuarioId) {
    String token = conexoes.buscarToken(usuarioId).orElseThrow(ConexaoXboxNaoEncontradaException::new);
    List<ClienteXbl.TituloXbl> titulos = xbl.buscarBiblioteca(token);
    for (ClienteXbl.TituloXbl titulo : titulos) {
      if (titulo == null || titulo.titleId() == null) continue;
      ClienteXbl.AchievementXbl conquistas = titulo.achievement();
      conexoes.upsertJogoBiblioteca(
          usuarioId,
          titulo.titleId(),
          titulo.name(),
          titulo.displayImage(),
          conquistas == null ? 0 : conquistas.currentGamerscore(),
          conquistas == null ? 0 : conquistas.totalGamerscore(),
          conquistas == null ? 0 : conquistas.currentGamerscore(),
          conquistas == null ? 0 : conquistas.totalGamerscore(),
          titulo.titleHistory() == null ? null : titulo.titleHistory().lastTimePlayed());
    }
    conexoes.marcarBibliotecaSincronizada(usuarioId);
  }

  // Usado pelo perfil publico/proprio pra montar a biblioteca combinada (Steam + Xbox).
  // Reaproveita o tipo JogoBibliotecaSteam (com plataforma="xbox") pra nao duplicar toda a
  // logica de biblioteca/platinados/filtro no frontend. O titleId da Xbox e mapeado pra
  // "appId" (int) so como identificador tecnico; titulos com id fora da faixa de int (raro)
  // sao ignorados em vez de quebrar a sincronizacao inteira.
  public List<ServicoConexoesSteam.JogoBibliotecaSteam> biblioteca(String usuarioId) {
    return conexoes.listarBiblioteca(usuarioId).stream()
        .map(jogo -> {
          try {
            int appId = Integer.parseInt(jogo.titleId());
            return new ServicoConexoesSteam.JogoBibliotecaSteam(appId, jogo.titulo(), 0, null, jogo.conquistasDesbloqueadas(), jogo.conquistasTotal(), jogo.capaUrl(), null, null, "xbox");
          } catch (NumberFormatException erro) {
            return null;
          }
        })
        .filter(jogo -> jogo != null)
        .toList();
  }

  static class ConexaoXboxNaoEncontradaException extends RuntimeException {}

  public record StatusConexaoXbox(boolean conectada, String gamertag, String avatarUrl, Integer gamerscore, String ultimoErro) {
    static StatusConexaoXbox desconectada() {
      return new StatusConexaoXbox(false, null, null, null, null);
    }
  }

  static class XblNaoConfiguradoException extends RuntimeException {}
  static class FalhaAoConectarXboxException extends RuntimeException {
    FalhaAoConectarXboxException(String mensagem) {
      super(mensagem);
    }
  }
}
