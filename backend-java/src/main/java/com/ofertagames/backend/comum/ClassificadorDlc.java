package com.ofertagames.backend.comum;

import java.util.regex.Pattern;

public final class ClassificadorDlc {
  private static final String REGEX_SQL = "(dlc|season pass|soundtrack|art book|skin set|skin pack|booster pack|expansion|add-on|monk decipher|demonic weapon pack|foundation boost|arcane boost|lion heart pack|ancient labyrinth)";
  private static final Pattern PADRAO = Pattern.compile(REGEX_SQL, Pattern.CASE_INSENSITIVE);

  private ClassificadorDlc() {}

  public static boolean pareceDlc(String titulo) {
    return titulo != null && PADRAO.matcher(titulo).find();
  }

  public static String condicaoDlcSql(String alias) {
    String titulo = "lower(coalesce(" + alias + ".title, ''))";
    return "(" + alias + ".is_dlc = true OR (" + alias + ".is_dlc IS NULL AND " + titulo + " ~ '" + REGEX_SQL + "'))";
  }

  public static String filtroApenasJogosSql(String alias) {
    return "AND NOT " + condicaoDlcSql(alias);
  }
}
