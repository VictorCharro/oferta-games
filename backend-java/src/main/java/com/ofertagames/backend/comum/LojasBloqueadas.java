package com.ofertagames.backend.comum;

import java.util.Set;

/**
 * Lojas excluidas de toda leitura do catalogo (listagem, home, busca, detalhe, favoritos e fila
 * de coleta).
 *
 * <p>Motivos variam por loja: link que nao abre (GreenManGaming, AllYouPay/AllYouPlay, PlanetPlay,
 * PlayerLand, JoyBuggy, WinGameStore, MacGameStore, Humble), preco exibido em moeda estrangeira
 * sem variante BR (GamesPlanet US/FR/DE/UK) e decisao de produto (GOG).
 *
 * <p>Ofertas dessas lojas continuam gravadas em {@code offers} — o bloqueio e so na leitura. Ao
 * incluir uma loja nova, atualizar {@link #NOMES_NORMALIZADOS} <b>e</b> {@link #REGEX_SQL}: sao
 * duas listas paralelas, e se sairem de sincronia o Java e o SQL passam a filtrar coisas
 * diferentes.
 */
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

  /**
   * Compara ignorando maiuscula, espaco e pontuacao, entao "Humble Store", "humble-store" e
   * "HumbleStore" sao o mesmo nome. Null vira {@code false}.
   */
  public static boolean contem(String loja) {
    return loja != null && NOMES_NORMALIZADOS.contains(normalizar(loja));
  }

  /**
   * Fragmento que comeca com {@code AND}, pra concatenar em WHERE ja existente (ver
   * {@code WHERE 1=1} nas queries do catalogo).
   *
   * @param alias alias da tabela {@code offers} — precisa ser literal do codigo, nunca valor de
   *     request (e interpolado direto no SQL)
   */
  public static String filtroSql(String alias) {
    return "AND regexp_replace(lower(" + alias + ".store_name), '[^a-z0-9]', '', 'g') !~ '" + REGEX_SQL + "'";
  }

  private static String normalizar(String loja) {
    return loja.toLowerCase().replaceAll("[^a-z0-9]", "");
  }
}
