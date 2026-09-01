package com.ofertagames.backend.comum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class IconeConquistaTest {
  private static final String COMPLETA =
      "https://steamcdn-a.akamaihd.net/steamcommunity/public/images/apps/620/WAKE_UP.jpg";
  private static final String COMPACTA = "620/WAKE_UP.jpg";

  @Test
  void compactarTiraOPrefixoDoCdn() {
    assertEquals(COMPACTA, IconeConquista.compactar(COMPLETA));
  }

  @Test
  void expandirRecolocaOPrefixo() {
    assertEquals(COMPLETA, IconeConquista.expandir(COMPACTA));
  }

  @Test
  void idaEVoltaPreservaAUrl() {
    assertEquals(COMPLETA, IconeConquista.expandir(IconeConquista.compactar(COMPLETA)));
  }

  /**
   * O backend e implantado antes da migration, entao por um intervalo convivem linhas ja compactas
   * e linhas ainda completas. Expandir precisa aceitar as duas, senao os icones quebram nesse meio
   * tempo.
   */
  @Test
  void expandirDevolveUrlCompletaIntacta() {
    assertEquals(COMPLETA, IconeConquista.expandir(COMPLETA));
  }

  /** Se a Steam trocar de CDN, o valor novo entra inteiro em vez de virar caminho relativo. */
  @Test
  void urlDeOutroDominioNaoEMutilada() {
    String outra = "https://cdn.cloudflare.steamstatic.com/outro/caminho/x.jpg";
    assertEquals(outra, IconeConquista.compactar(outra));
    assertEquals(outra, IconeConquista.expandir(outra));
  }

  @Test
  void nuloEVazioPassamSemErro() {
    assertNull(IconeConquista.compactar(null));
    assertNull(IconeConquista.expandir(null));
    assertEquals("", IconeConquista.expandir(""));
  }
}
