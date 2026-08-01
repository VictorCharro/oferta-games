package com.ofertagames.backend.comum;

import java.util.Set;

public final class LojasBloqueadas {
  private static final Set<String> NOMES_NORMALIZADOS = Set.of(
      "greenmangaming",
      "allyoupay",
      "allyouplay",
      "planetplay",
      "playerland",
      "joybuggy",
      "wingamestore",
      "macgamestore",
      "humblestore",
      "humblebundle",
      "gamesplanetus",
      "gamesplanetfr",
      "gamesplanetde",
      "gamesplanetuk",
      "gog"
  );

  // Ancorado (^...$) pra exigir nome normalizado igual, nao so contido — "gog" sem ancora
  // bateria em qualquer loja futura que so contivesse esse trecho no nome.
  private static final String REGEX_SQL = "^(greenmangaming|allyoupay|allyouplay|planetplay|playerland|joybuggy|wingamestore|macgamestore|humblestore|humblebundle|gamesplanetus|gamesplanetfr|gamesplanetde|gamesplanetuk|gog)$";

  private LojasBloqueadas() {}

  public static boolean contem(String loja) {
    return loja != null && NOMES_NORMALIZADOS.contains(normalizar(loja));
  }

  public static String filtroSql(String alias) {
    return "AND regexp_replace(lower(" + alias + ".store_name), '[^a-z0-9]', '', 'g') !~ '" + REGEX_SQL + "'";
  }

  private static String normalizar(String loja) {
    return loja.toLowerCase().replaceAll("[^a-z0-9]", "");
  }
}
