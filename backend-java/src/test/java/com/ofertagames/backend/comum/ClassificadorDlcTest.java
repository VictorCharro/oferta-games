package com.ofertagames.backend.comum;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClassificadorDlcTest {

  @Test
  void reconheceDlcPeloTitulo() {
    assertTrue(ClassificadorDlc.pareceDlc("Cyberpunk 2077: Phantom Liberty DLC"));
    assertTrue(ClassificadorDlc.pareceDlc("The Witcher 3 - Season Pass"));
    assertTrue(ClassificadorDlc.pareceDlc("Original Soundtrack"));
    assertTrue(ClassificadorDlc.pareceDlc("Deluxe Expansion Pack"));
  }

  @Test
  void naoReconheceJogoComumComoDlc() {
    assertFalse(ClassificadorDlc.pareceDlc("Baldur's Gate 3"));
    assertFalse(ClassificadorDlc.pareceDlc("Cult of the Lamb"));
    assertFalse(ClassificadorDlc.pareceDlc(null));
  }

  @Test
  void buscaEInsensivelACaixa() {
    assertTrue(ClassificadorDlc.pareceDlc("some game DLC"));
    assertTrue(ClassificadorDlc.pareceDlc("some game dlc"));
    assertTrue(ClassificadorDlc.pareceDlc("some game Dlc"));
  }

  @Test
  void condicaoDlcSqlCombinaFlagEHeuristicaDeTitulo() {
    String condicao = ClassificadorDlc.condicaoDlcSql("g");
    assertTrue(condicao.contains("g.is_dlc = true"));
    assertTrue(condicao.contains("g.is_dlc IS NULL"));
  }

  @Test
  void filtroApenasJogosSqlNegaACondicaoDeDlc() {
    String filtro = ClassificadorDlc.filtroApenasJogosSql("g");
    assertTrue(filtro.startsWith("AND NOT ("));
  }
}
