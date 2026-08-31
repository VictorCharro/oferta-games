package com.ofertagames.backend.comum;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Esconde do catalogo itens que a ITAD lista junto com jogos mas que nao sao jogo: cursos,
 * certificacoes, bundles de programacao/seguranca e coletaneas musicais.
 *
 * <p>Diferente de {@link LojasBloqueadas}, aqui Java e SQL compartilham a mesma constante
 * {@link #REGEX_SQL}, entao nao ha risco das duas listas divergirem.
 */
public final class ConteudosNaoJogos {
  private static final String REGEX_SQL = "(certification|e[- ]?learning|online business|programming bundle|cybersecurity|masterclass|tutorial|training course|course bundle|kali linux|phonk|hip hop|music bundle)";
  private static final Pattern PADRAO = Pattern.compile(REGEX_SQL, Pattern.CASE_INSENSITIVE);

  private ConteudosNaoJogos() {}

  /** Busca os termos em qualquer posicao do titulo, ignorando maiuscula. Null vira {@code false}. */
  public static boolean contem(String titulo) {
    return titulo != null && PADRAO.matcher(titulo.toLowerCase(Locale.ROOT)).find();
  }

  /**
   * Fragmento que comeca com {@code AND}, pra concatenar em WHERE ja existente.
   *
   * @param alias alias da tabela {@code games} — precisa ser literal do codigo, nunca valor de
   *     request (e interpolado direto no SQL)
   */
  public static String filtroSql(String alias) {
    return "AND lower(coalesce(" + alias + ".title, '')) !~ '" + REGEX_SQL + "'";
  }
}
