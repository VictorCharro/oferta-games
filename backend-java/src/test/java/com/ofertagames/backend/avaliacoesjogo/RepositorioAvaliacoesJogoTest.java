package com.ofertagames.backend.avaliacoesjogo;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;

class RepositorioAvaliacoesJogoTest {

  /**
   * Visitante deslogado: a coluna meu_voto precisa ser NULL com tipo boolean. Com NULL puro o
   * Postgres devolve text e o mapear (getObject(..., Boolean.class)) quebrava em todo jogo que tinha
   * avaliacao, com 500 em GET /api/games/{slug}/reviews (erro real de 26/09/2026).
   */
  @Test void visitanteDeslogadoRecebeMeuVotoComoBooleanNulo() {
    JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    new RepositorioAvaliacoesJogo(jdbc).listar(1L, null);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).sql(sql.capture());
    assertTrue(sql.getValue().contains("NULL::boolean AS meu_voto"), sql.getValue());
  }
}
