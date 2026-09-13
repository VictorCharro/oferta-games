package com.ofertagames.backend.comum;

import java.util.List;

/**
 * Esconde do catalogo itens que a ITAD lista junto com jogos mas que nao sao jogo: cursos,
 * certificacoes, bundles de programacao/seguranca e coletaneas musicais.
 *
 * <p>Java e SQL usam a mesma lista {@link #TERMOS}, entao nao ha risco das duas checagens
 * divergirem. Sem regex de proposito — ver {@link TermosSql}.
 */
public final class ConteudosNaoJogos {
  // "elearning/e-learning/e learning" sao as tres formas que o regex antigo e[- ]?learning cobria.
  private static final List<String> TERMOS = TermosSql.validar(List.of(
      "certification",
      "elearning",
      "e-learning",
      "e learning",
      "online business",
      "programming bundle",
      "cybersecurity",
      "masterclass",
      "tutorial",
      "training course",
      "course bundle",
      "kali linux",
      "phonk",
      "hip hop",
      "music bundle"
  ));

  private ConteudosNaoJogos() {}

  /** Busca os termos em qualquer posicao do titulo, ignorando maiuscula. Null vira {@code false}. */
  public static boolean contem(String titulo) {
    return TermosSql.contemAlgum(titulo, TERMOS);
  }

  /**
   * Fragmento que comeca com {@code AND}, pra concatenar em WHERE ja existente.
   *
   * @param alias alias da tabela {@code games} — precisa ser literal do codigo, nunca valor de
   *     request (e interpolado direto no SQL)
   */
  public static String filtroSql(String alias) {
    return "AND NOT " + TermosSql.contemAlgumSql(alias + ".title", TERMOS);
  }
}
