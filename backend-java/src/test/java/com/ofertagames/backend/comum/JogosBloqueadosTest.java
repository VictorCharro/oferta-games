package com.ofertagames.backend.comum;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JogosBloqueadosTest {

  @Test
  void reconheceOSlugBloqueado() {
    assertTrue(JogosBloqueados.contemSlug("tell-me-why-chapter-1"));
  }

  @Test
  void naoReconheceOutrosSlugs() {
    assertFalse(JogosBloqueados.contemSlug("baldurs-gate-3"));
    assertFalse(JogosBloqueados.contemSlug(null));
  }

  @Test
  void filtroSqlReferenciaAliasDaTabela() {
    String filtro = JogosBloqueados.filtroSql("g");
    assertTrue(filtro.contains("g.slug <>"));
  }
}
