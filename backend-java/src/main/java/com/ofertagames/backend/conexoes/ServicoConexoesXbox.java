package com.ofertagames.backend.conexoes;

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
