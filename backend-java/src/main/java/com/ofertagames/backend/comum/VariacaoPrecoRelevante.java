package com.ofertagames.backend.comum;

import java.math.BigDecimal;

/**
 * Decide se uma mudanca de preco e real ou so ruido de conversao cambial.
 *
 * <p>Os precos da ITAD chegam convertidos para BRL, e a cotacao oscilando faz o mesmo produto
 * variar centavos de uma sincronizacao pra outra. Sem um limiar isso poluia tres coisas ao mesmo
 * tempo: o grafico de historico virava serrilha, {@code price_history} crescia com metade de lixo
 * (51% das variacoes gravadas ficavam abaixo de 1%, medido em 31/08/2026) e o usuario recebia
 * alerta de "queda de preco" de um centavo.
 *
 * <p><b>Regra:</b> a variacao conta quando e de pelo menos {@value #PERCENTUAL_MINIMO}% sobre o
 * valor anterior. Transicao entre gratuito e pago sempre conta, em qualquer direcao, porque nao ha
 * percentual que represente isso.
 *
 * <p>O limiar e sempre relativo ao ultimo valor <b>registrado</b>, nunca ao imediatamente anterior.
 * Isso e o que permite que uma sequencia de variacoes de 0,9% ainda seja capturada quando o desvio
 * acumulado passa de 1% — do contrario uma subida real e lenta ficaria invisivel pra sempre.
 */
public final class VariacaoPrecoRelevante {
  /** Percentual minimo de variacao para considerar que o preco mudou de verdade. */
  public static final double PERCENTUAL_MINIMO = 1.0;

  private static final BigDecimal FATOR = BigDecimal.valueOf(PERCENTUAL_MINIMO / 100.0);

  private VariacaoPrecoRelevante() {}

  /**
   * Versao Java da regra, para quando os dois valores ja estao em memoria.
   *
   * @return {@code true} quando nao ha valor anterior (primeiro registro), quando um dos lados e
   *     gratuito e o outro nao, ou quando a diferenca atinge o percentual minimo
   */
  public static boolean relevante(BigDecimal anterior, BigDecimal atual) {
    if (atual == null) return false;
    if (anterior == null) return true;
    if (anterior.compareTo(atual) == 0) return false;

    boolean anteriorGratis = anterior.signum() == 0;
    boolean atualGratis = atual.signum() == 0;
    if (anteriorGratis || atualGratis) return true;

    BigDecimal diferenca = atual.subtract(anterior).abs();
    return diferenca.compareTo(anterior.multiply(FATOR)) >= 0;
  }

  /**
   * Mesma regra como expressao booleana SQL, para usar dentro de {@code INSERT ... SELECT}.
   *
   * <p>Trata {@code anterior} nulo como "primeiro registro" (sempre relevante), o que cobre o caso
   * de um jogo que ainda nao tem nenhuma linha de historico.
   *
   * @param atual expressao SQL do preco novo (ex: {@code "atual.preco"})
   * @param anterior expressao SQL do ultimo preco registrado (ex: {@code "ultimo.price"}); precisa
   *     ser referencia de coluna, nao subquery, porque aparece varias vezes na expressao
   */
  public static String condicaoSql(String atual, String anterior) {
    return "("
        + anterior + " IS NULL"
        + " OR (" + atual + " <> " + anterior
        + "   AND (" + anterior + " = 0 OR " + atual + " = 0"
        + "     OR abs(" + atual + " - " + anterior + ") >= " + anterior + " * " + (PERCENTUAL_MINIMO / 100.0)
        + "   )"
        + " )"
        + ")";
  }
}
