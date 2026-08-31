package com.ofertagames.backend.comum;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class VariacaoPrecoRelevanteTest {

  private static BigDecimal reais(String valor) {
    return new BigDecimal(valor);
  }

  @Test
  void primeiroRegistroSempreConta() {
    assertTrue(VariacaoPrecoRelevante.relevante(null, reais("10.00")));
  }

  @Test
  void precoIgualNaoConta() {
    assertFalse(VariacaoPrecoRelevante.relevante(reais("10.24"), reais("10.24")));
  }

  @Test
  void ruidoCambialDeCentavosNaoConta() {
    // Caso real de producao (hotline-miami): a cotacao balancando gerava uma linha de historico
    // por centavo, enchendo o grafico de serrilha.
    assertFalse(VariacaoPrecoRelevante.relevante(reais("10.24"), reais("10.25")));
    assertFalse(VariacaoPrecoRelevante.relevante(reais("10.31"), reais("10.32")));
    assertFalse(VariacaoPrecoRelevante.relevante(reais("10.29"), reais("10.24")));
  }

  @Test
  void quedaRealConta() {
    assertTrue(VariacaoPrecoRelevante.relevante(reais("10.29"), reais("9.96")));
    assertTrue(VariacaoPrecoRelevante.relevante(reais("6.59"), reais("10.24")));
  }

  @Test
  void contaNosDoisSentidos() {
    assertTrue(VariacaoPrecoRelevante.relevante(reais("100.00"), reais("90.00")));
    assertTrue(VariacaoPrecoRelevante.relevante(reais("90.00"), reais("100.00")));
  }

  @Test
  void limiarEProporcionalAoPreco() {
    // 1 real em 300 e ruido; o mesmo 1 real em 10 e mudanca de verdade.
    assertFalse(VariacaoPrecoRelevante.relevante(reais("300.00"), reais("299.00")));
    assertTrue(VariacaoPrecoRelevante.relevante(reais("10.00"), reais("9.00")));
  }

  @Test
  void exatamenteNoLimiarConta() {
    assertTrue(VariacaoPrecoRelevante.relevante(reais("100.00"), reais("99.00")));
    assertFalse(VariacaoPrecoRelevante.relevante(reais("100.00"), reais("99.50")));
  }

  @Test
  void transicaoEntreGratuitoEPagoSempreConta() {
    // Nao ha percentual que represente "virou gratis": 100% off precisa passar mesmo sendo
    // matematicamente indefinido a partir de zero.
    assertTrue(VariacaoPrecoRelevante.relevante(reais("59.90"), BigDecimal.ZERO));
    assertTrue(VariacaoPrecoRelevante.relevante(BigDecimal.ZERO, reais("59.90")));
    // Continuar gratuito nao e mudanca.
    assertFalse(VariacaoPrecoRelevante.relevante(BigDecimal.ZERO, BigDecimal.ZERO));
  }

  @Test
  void precoAtualNuloNaoConta() {
    assertFalse(VariacaoPrecoRelevante.relevante(reais("10.00"), null));
  }
}
