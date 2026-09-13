package com.ofertagames.backend.comum;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Busca de termos em titulo que funciona igual em Java e em SQL, sem regex.
 *
 * <p>Existe porque o regex de alternativas ({@code ~ '(termo1|termo2|...)'}) e muito lento no
 * Postgres: ~30 µs por linha, 3,2s pra varrer os 110 mil titulos do catalogo. Com
 * {@code LIKE ANY (ARRAY['%termo1%', ...])} o mesmo filtro leva 235ms e devolve exatamente as
 * mesmas linhas (medido na VM em 13/09/2026, issue #16).
 *
 * <p>Os termos sao comparados em minusculas e por "contem", que e o que o regex sem ancora fazia.
 * Como viram literal SQL, nao podem ter aspas nem os curingas do LIKE — {@link #validar} recusa na
 * carga da classe, e os testes de cada lista chamam isso.
 */
final class TermosSql {
  private TermosSql() {}

  static List<String> validar(List<String> termos) {
    for (String termo : termos) {
      if (termo.isBlank() || !termo.equals(termo.toLowerCase(Locale.ROOT)) || termo.matches(".*['%_\\\\].*")) {
        throw new IllegalStateException("Termo invalido pra filtro SQL: " + termo);
      }
    }
    return List.copyOf(termos);
  }

  /** Titulo (qualquer caixa) contem algum dos termos. Null vira {@code false}. */
  static boolean contemAlgum(String titulo, List<String> termos) {
    if (titulo == null) return false;
    String minusculo = titulo.toLowerCase(Locale.ROOT);
    return termos.stream().anyMatch(minusculo::contains);
  }

  /** Expressao booleana SQL equivalente a {@link #contemAlgum}, sobre a coluna informada. */
  static String contemAlgumSql(String coluna, List<String> termos) {
    return termos.stream()
        .map(termo -> "'%" + termo + "%'")
        .collect(Collectors.joining(",", "lower(coalesce(" + coluna + ", '')) LIKE ANY (ARRAY[", "])"));
  }
}
