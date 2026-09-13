package com.ofertagames.backend.comum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LimitePorJanelaTest {

  /** Relogio que o teste avanca na mao. */
  private static final class RelogioManual extends Clock {
    private Instant agora = Instant.parse("2026-09-13T12:00:00Z");
    void avancar(Duration d) { agora = agora.plus(d); }
    @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    @Override public Instant instant() { return agora; }
  }

  @Test
  void recusaAcimaDoTetoELiberaQuandoAJanelaVira() {
    RelogioManual relogio = new RelogioManual();
    LimitePorJanela limite = new LimitePorJanela(3, Duration.ofHours(1), relogio);

    assertTrue(limite.tentarConsumir());
    assertTrue(limite.tentarConsumir());
    assertTrue(limite.tentarConsumir());
    assertFalse(limite.tentarConsumir());

    relogio.avancar(Duration.ofMinutes(59));
    assertFalse(limite.tentarConsumir());
    assertEquals(60, limite.segundosAteLiberar());

    relogio.avancar(Duration.ofMinutes(1));
    assertTrue(limite.tentarConsumir());
  }
}
