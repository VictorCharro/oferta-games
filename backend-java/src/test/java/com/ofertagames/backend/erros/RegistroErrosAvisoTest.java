package com.ofertagames.backend.erros;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ofertagames.backend.alertas.NotificadorWhatsapp;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

/**
 * Cobre a regra de quando {@link RegistroErros#registrar} aciona
 * {@link NotificadorWhatsapp} — ver o javadoc da classe pro porque de so avisar na primeira
 * ocorrencia e na reabertura, nunca em toda ocorrencia de um erro ja ativo.
 *
 * <p>Casa o {@code .sql(...)} por trecho da consulta (nao o texto inteiro), pra o teste nao
 * quebrar por uma reformatacao de espacos que nao muda o comportamento.
 */
class RegistroErrosAvisoTest {

  private static StatementSpec sqlContendo(JdbcClient jdbc, String trecho) {
    return jdbc.sql(argThat(sql -> sql != null && sql.contains(trecho)));
  }

  @Test void assinaturaNuncaVistaAvisaErroNovo() {
    JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    NotificadorWhatsapp whatsapp = mock(NotificadorWhatsapp.class);

    when(sqlContendo(jdbc, "SELECT resolved_at IS NOT NULL").param(anyString(), any()).query(Boolean.class).optional())
        .thenReturn(Optional.empty());
    when(sqlContendo(jdbc, "UPDATE app_errors").param(anyString(), any()).param(anyString(), any()).param(anyString(), any()).update())
        .thenReturn(0);
    when(sqlContendo(jdbc, "SELECT count(*)").query(Long.class).single()).thenReturn(0L);

    new RegistroErros(jdbc, whatsapp).registrar("backend", "Falha nova", null, "/jogo/x", null);

    verify(whatsapp).avisarErroNovo("backend", "Falha nova", "/jogo/x");
    verify(whatsapp, never()).avisarErroReaberto(any(), any(), any());
  }

  @Test void erroJaAtivoSoIncrementaSemAvisarDeNovo() {
    JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    NotificadorWhatsapp whatsapp = mock(NotificadorWhatsapp.class);

    // Existia e ja estava ATIVO (nao resolvido) - so mais uma ocorrencia do mesmo erro conhecido.
    when(sqlContendo(jdbc, "SELECT resolved_at IS NOT NULL").param(anyString(), any()).query(Boolean.class).optional())
        .thenReturn(Optional.of(false));
    when(sqlContendo(jdbc, "UPDATE app_errors").param(anyString(), any()).param(anyString(), any()).param(anyString(), any()).update())
        .thenReturn(1);

    new RegistroErros(jdbc, whatsapp).registrar("backend", "Falha conhecida", null, "/jogo/x", null);

    verifyNoInteractions(whatsapp);
  }

  @Test void erroResolvidoQueVoltaAAcontecerAvisaReaberto() {
    JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    NotificadorWhatsapp whatsapp = mock(NotificadorWhatsapp.class);

    when(sqlContendo(jdbc, "SELECT resolved_at IS NOT NULL").param(anyString(), any()).query(Boolean.class).optional())
        .thenReturn(Optional.of(true));
    when(sqlContendo(jdbc, "UPDATE app_errors").param(anyString(), any()).param(anyString(), any()).param(anyString(), any()).update())
        .thenReturn(1);

    new RegistroErros(jdbc, whatsapp).registrar("backend", "Falha que voltou", null, "/jogo/x", null);

    verify(whatsapp).avisarErroReaberto("backend", "Falha que voltou", "/jogo/x");
    verify(whatsapp, never()).avisarErroNovo(any(), any(), any());
  }

  /** {@code registrar} nunca pode derrubar o chamador (o proprio caminho de tratar um erro). */
  @Test void falhaAoConsultarNaoLancaEExigeAvisoManual() {
    JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    NotificadorWhatsapp whatsapp = mock(NotificadorWhatsapp.class);
    when(sqlContendo(jdbc, "SELECT resolved_at IS NOT NULL").param(anyString(), any()).query(Boolean.class).optional())
        .thenThrow(new RuntimeException("banco fora do ar"));

    new RegistroErros(jdbc, whatsapp).registrar("backend", "Falha", null, "/x", null);

    verifyNoInteractions(whatsapp);
  }
}
