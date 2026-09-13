package com.ofertagames.backend.comum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ParametrosPublicosTest {

  @Test
  void valoresDesconhecidosCaemNoPadrao() {
    assertEquals("discount", ParametrosPublicos.ordenacaoDescontos("xyz"));
    assertEquals("discount", ParametrosPublicos.ordenacaoDescontos(null));
    assertEquals("rank", ParametrosPublicos.ordenacaoDescontos(" RANK "));
    assertEquals("rank", ParametrosPublicos.ordenacaoCatalogo("'; drop table games; --"));
    assertEquals("price_asc", ParametrosPublicos.ordenacaoCatalogo("price_asc"));
    assertEquals("all", ParametrosPublicos.tipo("qualquer"));
    assertEquals("dlc", ParametrosPublicos.tipo("DLC"));
    assertEquals("all", ParametrosPublicos.plataforma("nintendo"));
    assertEquals("xbox", ParametrosPublicos.plataforma("xbox"));
  }

  /** O ataque medido na auditoria: variar size de 1 a 200 no deals/top gerava 200 chaves frias. */
  @Test
  void variarTamanhoDoCatalogoNaoMultiplicaChaves() {
    Set<Integer> tamanhos = new HashSet<>();
    for (int size = -5; size <= 500; size++) tamanhos.add(ParametrosPublicos.tamanhoCatalogo(size));
    assertEquals(Set.of(20, 40), tamanhos);
  }

  @Test
  void tamanhoDeDescontosFicaEntreUmEOTopo() {
    assertEquals(1, ParametrosPublicos.tamanhoDescontos(0));
    assertEquals(50, ParametrosPublicos.tamanhoDescontos(50));
    assertEquals(ParametrosPublicos.TAMANHO_TOPO_DESCONTOS, ParametrosPublicos.tamanhoDescontos(999));
  }

  @Test
  void paginaTemTeto() {
    assertEquals(0, ParametrosPublicos.pagina(-1));
    assertEquals(3, ParametrosPublicos.pagina(3));
    assertEquals(ParametrosPublicos.PAGINA_MAXIMA, ParametrosPublicos.pagina(1_000_000));
  }

  @Test
  void precosViramReaisInteirosNaDirecaoQueIncluiMaisJogos() {
    assertEquals(49.0, ParametrosPublicos.precoMinimo(49.99));
    assertEquals(50.0, ParametrosPublicos.precoMaximo(49.01));
    assertNull(ParametrosPublicos.precoMinimo(0.0));
    assertNull(ParametrosPublicos.precoMinimo(Double.NaN));
    assertNull(ParametrosPublicos.precoMaximo(-3.0));
    assertNull(ParametrosPublicos.precoMaximo(999_999.0));
    assertEquals(ParametrosPublicos.PRECO_MAXIMO_UTIL, ParametrosPublicos.precoMinimo(999_999.0));
  }

  @Test
  void descontoMinimoInteiroEntreUmECem() {
    assertNull(ParametrosPublicos.descontoMinimo(0.0));
    assertNull(ParametrosPublicos.descontoMinimo(0.4));
    assertEquals(34.0, ParametrosPublicos.descontoMinimo(33.6));
    assertEquals(100.0, ParametrosPublicos.descontoMinimo(250.0));
  }

  @Test
  void buscaNormalizaCaixaEEspacos() {
    assertEquals("the witcher 3", ParametrosPublicos.busca("  The   Witcher\t3 "));
    assertNull(ParametrosPublicos.busca("   "));
    assertNull(ParametrosPublicos.busca(null));
    assertEquals(ParametrosPublicos.TAMANHO_MAXIMO_BUSCA, ParametrosPublicos.busca("a".repeat(500)).length());
  }

  @Test
  void lojasSaoFiltradasDeduplicadasEOrdenadas() {
    assertEquals(List.of("epic", "steam"), ParametrosPublicos.lojas("steam, EPIC,steam,loja-falsa,,"));
    assertEquals(List.of(), ParametrosPublicos.lojas("nada,valido"));
    assertEquals(List.of(), ParametrosPublicos.lojas(null));
  }
}
