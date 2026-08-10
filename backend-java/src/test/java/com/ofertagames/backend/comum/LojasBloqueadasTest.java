package com.ofertagames.backend.comum;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LojasBloqueadasTest {

  @Test
  void reconheceLojaBloqueadaIgnorandoCaixaEPontuacao() {
    assertTrue(LojasBloqueadas.contem("GOG"));
    assertTrue(LojasBloqueadas.contem("Green Man Gaming"));
    assertTrue(LojasBloqueadas.contem("gamesplanet-us"));
  }

  @Test
  void naoReconheceLojaPermitida() {
    assertFalse(LojasBloqueadas.contem("Steam"));
    assertFalse(LojasBloqueadas.contem("Nuuvem"));
    assertFalse(LojasBloqueadas.contem(null));
  }

  @Test
  void naoBateComNomeQueSoContemOTrecho() {
    // regex ancorado (^...$): "gog" nao deve bater em uma loja futura tipo "Gogolplex Games".
    assertFalse(LojasBloqueadas.contem("Gogolplex Games"));
  }
}
