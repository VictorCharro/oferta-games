package com.ofertagames.backend.comum;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lojas excluidas de toda leitura do catalogo (listagem, home, busca, detalhe, favoritos e fila
 * de coleta).
 *
 * <p>Motivos variam por loja: link que nao abre (GreenManGaming, AllYouPay/AllYouPlay, PlanetPlay,
 * PlayerLand, JoyBuggy, WinGameStore, MacGameStore, Humble), preco exibido em moeda estrangeira
 * sem variante BR (GamesPlanet US/FR/DE/UK) e decisao de produto (GOG).
 *
 * <p>Ofertas dessas lojas continuam gravadas em {@code offers} — o bloqueio e so na leitura.
 * {@link #NOMES_NORMALIZADOS} e a <b>unica</b> lista: alimenta o Java e o SQL, entao os dois nunca
 * filtram coisas diferentes.
 */
public final class LojasBloqueadas {
  private static final List<String> NOMES_NORMALIZADOS = List.of(
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

  private static final Set<String> CONJUNTO = Set.copyOf(NOMES_NORMALIZADOS);

  /**
   * Comparacao por <b>igualdade</b> contra uma lista, nao por regex.
   *
   * <p>Era {@code !~ '^(greenmangaming|...|gog)$'}, que da o mesmo resultado mas custava ~30 µs por
   * linha no Postgres: 4,9s so pra filtrar as 166 mil ofertas, e esse filtro aparece duas vezes no
   * ranking de descontos da Home (13s de cache frio, issue #16). {@code <> ALL (ARRAY[...])} com a
   * mesma normalizacao leva 148ms — medido na VM em 13/09/2026, com 0 linhas de diferenca entre as
   * duas formas. Igualdade tambem ja garante o "nome inteiro, nao so contido" que o regex ancorado
   * garantia ("gog" nao pega "Gogolplex Games").
   *
   * <p>NULL continua de fora do resultado nas duas formas: {@code NULL <> ALL (...)} e NULL.
   */
  private static final String LISTA_SQL = NOMES_NORMALIZADOS.stream()
      .map(nome -> "'" + nome + "'")
      .collect(Collectors.joining(",", "ARRAY[", "]"));

  private LojasBloqueadas() {}

  /**
   * Compara ignorando maiuscula, espaco e pontuacao, entao "Humble Store", "humble-store" e
   * "HumbleStore" sao o mesmo nome. Null vira {@code false}.
   */
  public static boolean contem(String loja) {
    return loja != null && CONJUNTO.contains(normalizar(loja));
  }

  /**
   * Fragmento que comeca com {@code AND}, pra concatenar em WHERE ja existente (ver
   * {@code WHERE 1=1} nas queries do catalogo).
   *
   * @param alias alias da tabela {@code offers} — precisa ser literal do codigo, nunca valor de
   *     request (e interpolado direto no SQL)
   */
  public static String filtroSql(String alias) {
    return "AND regexp_replace(lower(" + alias + ".store_name), '[^a-z0-9]', '', 'g') <> ALL (" + LISTA_SQL + ")";
  }

  /** Exposto pro teste garantir que nenhum nome quebra o literal SQL. */
  static List<String> nomesNormalizados() {
    return NOMES_NORMALIZADOS;
  }

  private static String normalizar(String loja) {
    return loja.toLowerCase().replaceAll("[^a-z0-9]", "");
  }
}
