package com.ofertagames.backend.comum;

import java.util.regex.Pattern;

/**
 * Separa DLC de jogo base, combinando o sinal da Steam com heuristica de titulo.
 *
 * <p>Os dois sinais sao necessarios: a Steam classifica trilha sonora como {@code type: "music"}
 * (nao {@code "dlc"}), entao depender so dela deixava esses itens como jogo base. O mesmo regex
 * alimenta a checagem em Java e a condicao SQL, pra catalogo e sincronizacao nunca divergirem.
 */
public final class ClassificadorDlc {
  private static final String REGEX_SQL = "(dlc|season pass|soundtrack|art book|skin set|skin pack|booster pack|expansion|add-on|monk decipher|demonic weapon pack|foundation boost|arcane boost|lion heart pack|ancient labyrinth)";
  private static final Pattern PADRAO = Pattern.compile(REGEX_SQL, Pattern.CASE_INSENSITIVE);

  private ClassificadorDlc() {}

  /** Heuristica de titulo apenas — nao conhece {@code games.is_dlc}. Null vira {@code false}. */
  public static boolean pareceDlc(String titulo) {
    return titulo != null && PADRAO.matcher(titulo).find();
  }

  /**
   * Expressao booleana SQL que decide se a linha e DLC.
   *
   * <p>A heuristica de titulo so entra quando {@code is_dlc IS NULL} (ainda nao classificado pelo
   * job da Steam). Um jogo com {@code is_dlc = false} explicito continua jogo base mesmo que o
   * titulo bata no regex — a classificacao ja feita vence a heuristica.
   *
   * @param alias alias da tabela {@code games} na query; precisa ser literal do proprio codigo,
   *     nunca valor vindo de request (e interpolado direto no SQL)
   */
  public static String condicaoDlcSql(String alias) {
    String titulo = "lower(coalesce(" + alias + ".title, ''))";
    return "(" + alias + ".is_dlc = true OR (" + alias + ".is_dlc IS NULL AND " + titulo + " ~ '" + REGEX_SQL + "'))";
  }

  /**
   * Fragmento pronto pra concatenar numa clausula WHERE ja existente — comeca com {@code AND}.
   *
   * <p>Por isso as queries do catalogo abrem com {@code WHERE 1=1}: e o que permite concatenar
   * estes fragmentos em qualquer combinacao sem montar a condicao inicial na mao.
   */
  public static String filtroApenasJogosSql(String alias) {
    return "AND NOT " + condicaoDlcSql(alias);
  }
}
