package com.ofertagames.backend.notificacoes;

import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class RepositorioNotificacoesTest {
  @Test void quedaSemMonitoradorNaoConsultaCatalogoNemInsere() {
    var contas = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    var catalogo = mock(JdbcClient.class);
    String sql = "SELECT DISTINCT game_id FROM favorites WHERE game_id IN (:ids)";
    when(contas.sql(sql).param("ids", List.of(1L)).query(Long.class).list()).thenReturn(List.of());
    var repositorio = new RepositorioNotificacoes(contas, catalogo);
    repositorio.registrarQuedas(Map.of(1L, BigDecimal.TEN), Map.of(1L, BigDecimal.ONE));
    verifyNoInteractions(catalogo);
    verify(contas).sql(sql);
  }
  @Test void loteSemQuedaNaoConsultaNenhumBanco() {
    var contas = mock(JdbcClient.class);
    var catalogo = mock(JdbcClient.class);
    new RepositorioNotificacoes(contas, catalogo).registrarQuedas(Map.of(1L, BigDecimal.ONE), Map.of(1L, BigDecimal.TEN));
    verifyNoInteractions(contas, catalogo);
  }
}
