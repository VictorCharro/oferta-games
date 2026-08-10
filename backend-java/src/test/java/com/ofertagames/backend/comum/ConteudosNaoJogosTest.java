package com.ofertagames.backend.comum;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConteudosNaoJogosTest {

  @Test
  void reconheceCursosECertificacoes() {
    assertTrue(ConteudosNaoJogos.contem("Complete Python Programming Bundle"));
    assertTrue(ConteudosNaoJogos.contem("AWS Certification Prep"));
    assertTrue(ConteudosNaoJogos.contem("Cybersecurity Masterclass"));
  }

  @Test
  void naoReconheceJogoComum() {
    assertFalse(ConteudosNaoJogos.contem("Baldur's Gate 3"));
    assertFalse(ConteudosNaoJogos.contem(null));
  }

  @Test
  void filtroSqlReferenciaAliasDaTabela() {
    String filtro = ConteudosNaoJogos.filtroSql("games");
    assertTrue(filtro.contains("games.title"));
  }
}
