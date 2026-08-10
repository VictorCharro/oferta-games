package com.ofertagames.backend.comum;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GeradorSlugTest {

  @Test
  void geraSlugSimples() {
    // Apostrofo tambem e substituido por hifen: nao e alfanumerico.
    assertEquals("baldur-s-gate-3", GeradorSlug.porTitulo("Baldur's Gate 3"));
  }

  @Test
  void removeAcentos() {
    assertEquals("resident-evil-4-remake", GeradorSlug.porTitulo("Resident Evil 4: Remake"));
  }

  @Test
  void colapsaEspacosEPontuacaoEmUmUnicoHifen() {
    assertEquals("god-of-war-ragnarok", GeradorSlug.porTitulo("God   of  War: Ragnarök"));
  }

  @Test
  void naoDeixaHifenNasPontas() {
    assertEquals("cult-of-the-lamb", GeradorSlug.porTitulo("-- Cult of the Lamb --"));
  }
}
