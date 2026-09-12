package com.ofertagames.backend.comum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class IconeConquistaTest {
  /** O que a Steam Web API devolve hoje no GetSchemaForGame. */
  private static final String LEGADA =
      "https://steamcdn-a.akamaihd.net/steamcommunity/public/images/apps/620/WAKE_UP.jpg";

  /** O caminho que serve arte nova tambem, usado pra montar a URL que vai pro frontend. */
  private static final String ATUAL =
      "https://shared.fastly.steamstatic.com/community_assets/images/apps/620/WAKE_UP.jpg";

  private static final String COMPACTA = "620/WAKE_UP.jpg";

  @Test
  void compactarTiraOPrefixoLegadoDoCdn() {
    assertEquals(COMPACTA, IconeConquista.compactar(LEGADA));
  }

  /** Se a Steam passar a mandar o caminho novo, tambem tem que compactar em vez de guardar inteiro. */
  @Test
  void compactarTiraOPrefixoAtualTambem() {
    assertEquals(COMPACTA, IconeConquista.compactar(ATUAL));
    assertEquals(
        COMPACTA,
        IconeConquista.compactar(
            "https://shared.akamai.steamstatic.com/community_assets/images/apps/620/WAKE_UP.jpg"));
  }

  /**
   * O ponto da mudanca de 12/09/2026: grava-se o que a Steam manda (caminho legado) e serve-se o
   * caminho atual, que e o unico onde arte de conquista nova existe.
   */
  @Test
  void expandirServeOCaminhoAtualMesmoVindoDoLegado() {
    assertEquals(ATUAL, IconeConquista.expandir(IconeConquista.compactar(LEGADA)));
  }

  @Test
  void expandirRecolocaOPrefixoAtual() {
    assertEquals(ATUAL, IconeConquista.expandir(COMPACTA));
  }

  /**
   * O backend e implantado antes da migration, entao por um intervalo convivem linhas ja compactas
   * e linhas ainda completas. Expandir precisa aceitar as duas, senao os icones quebram nesse meio
   * tempo.
   */
  @Test
  void expandirDevolveUrlCompletaIntacta() {
    assertEquals(LEGADA, IconeConquista.expandir(LEGADA));
  }

  /** Se a Steam trocar de CDN de novo, o valor novo entra inteiro em vez de virar caminho relativo. */
  @Test
  void urlDeOutroDominioNaoEMutilada() {
    String outra = "https://cdn.exemplo.steamstatic.com/outro/caminho/x.jpg";
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
