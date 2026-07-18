package com.ofertagames.backend.avaliacoesjogo;

import com.ofertagames.backend.jogos.RepositorioJogos;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class ServicoAvaliacoesJogo {
  private static final int MAXIMO_CARACTERES_COMENTARIO = 2000;

  private final RepositorioAvaliacoesJogo avaliacoes;
  private final RepositorioJogos jogos;

  ServicoAvaliacoesJogo(RepositorioAvaliacoesJogo avaliacoes, RepositorioJogos jogos) {
    this.avaliacoes = avaliacoes;
    this.jogos = jogos;
  }

  public Optional<RespostaAvaliacoes> buscar(String slug, String visitanteId) {
    return jogos.buscarIdPorSlug(slug).map(gameId -> {
      ResumoAvaliacoes resumo = avaliacoes.resumo(gameId);
      AvaliacaoJogo minha = visitanteId == null ? null : avaliacoes.buscarMinha(gameId, visitanteId).orElse(null);
      List<AvaliacaoJogo> lista = avaliacoes.listar(gameId, visitanteId);
      return new RespostaAvaliacoes(resumo, minha, lista);
    });
  }

  public long salvar(String slug, String usuarioId, RequisicaoAvaliacao requisicao) {
    long gameId = jogos.buscarIdPorSlug(slug).orElseThrow(JogoNaoEncontradoException::new);
    if (requisicao == null || requisicao.nota() == null || requisicao.nota() < 1 || requisicao.nota() > 5) {
      throw new AvaliacaoInvalidaException("Informe uma nota de 1 a 5");
    }
    String comentario = requisicao.comentario() == null ? null : requisicao.comentario().trim();
    if (comentario != null && comentario.length() > MAXIMO_CARACTERES_COMENTARIO) {
      throw new AvaliacaoInvalidaException("Comentario deve ter ate " + MAXIMO_CARACTERES_COMENTARIO + " caracteres");
    }
    return avaliacoes.salvar(gameId, usuarioId, requisicao.nota(), comentario == null || comentario.isBlank() ? null : comentario);
  }

  public boolean excluir(String slug, String usuarioId) {
    long gameId = jogos.buscarIdPorSlug(slug).orElseThrow(JogoNaoEncontradoException::new);
    return avaliacoes.excluir(gameId, usuarioId);
  }

  public void votar(long reviewId, String usuarioId, boolean util) {
    if (!avaliacoes.existeAvaliacao(reviewId)) {
      throw new AvaliacaoNaoEncontradaException();
    }
    avaliacoes.votar(reviewId, usuarioId, util);
  }

  public static class JogoNaoEncontradoException extends RuntimeException {}
  public static class AvaliacaoNaoEncontradaException extends RuntimeException {}
  public static class AvaliacaoInvalidaException extends RuntimeException {
    public AvaliacaoInvalidaException(String mensagem) { super(mensagem); }
  }
}
