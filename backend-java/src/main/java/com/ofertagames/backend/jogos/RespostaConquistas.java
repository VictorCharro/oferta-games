package com.ofertagames.backend.jogos;

import java.util.List;

/**
 * @param coletando a lista veio vazia porque a coleta acabou de ser disparada, e nao porque o jogo
 *     nao tem conquistas. Serve pro frontend saber que vale reconsultar em alguns segundos, em vez
 *     de exigir um F5 do usuario — e, principalmente, pra <b>nao</b> reconsultar nos milhares de
 *     jogos que a Steam ja confirmou nao ter conquista nenhuma.
 */
public record RespostaConquistas(
    int total,
    int desbloqueadas,
    int percentualConcluido,
    ConquistaComProgresso proxima,
    List<ConquistaComProgresso> conquistas,
    boolean coletando) {

  public RespostaConquistas comColeta(boolean coletando) {
    return new RespostaConquistas(total, desbloqueadas, percentualConcluido, proxima, conquistas, coletando);
  }
}
