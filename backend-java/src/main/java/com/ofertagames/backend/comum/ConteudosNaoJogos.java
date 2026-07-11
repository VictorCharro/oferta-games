package com.ofertagames.backend.comum;

import java.util.Locale;
import java.util.regex.Pattern;

public final class ConteudosNaoJogos {
  private static final String REGEX_SQL = "(certification|e[- ]?learning|online business|programming bundle|cybersecurity|masterclass|tutorial|training course|course bundle|kali linux|phonk|hip hop|music bundle)";
  private static final Pattern PADRAO = Pattern.compile(REGEX_SQL, Pattern.CASE_INSENSITIVE);

  private ConteudosNaoJogos() {}

  public static boolean contem(String titulo) {
    return titulo != null && PADRAO.matcher(titulo.toLowerCase(Locale.ROOT)).find();
  }

  public static String filtroSql(String alias) {
    return "AND lower(coalesce(" + alias + ".title, '')) !~ '" + REGEX_SQL + "'";
  }
}
