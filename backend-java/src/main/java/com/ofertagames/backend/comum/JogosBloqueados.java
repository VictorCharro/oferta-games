package com.ofertagames.backend.comum;

public final class JogosBloqueados {
  private static final String SLUG_TELL_ME_WHY_CHAPTER_1 = "tell-me-why-chapter-1";
  private static final String URL_TELL_ME_WHY_CHAPTER_1 = "https://itad.link/019e8518-404a-709b-ae47-a4ef949552ea/";

  private JogosBloqueados() {}

  public static boolean contemSlug(String slug) {
    return SLUG_TELL_ME_WHY_CHAPTER_1.equals(slug);
  }

  public static String filtroSql(String alias) {
    return "AND " + alias + ".slug <> '" + SLUG_TELL_ME_WHY_CHAPTER_1 + "'"
        + " AND NOT EXISTS ("
        + "SELECT 1 FROM offers oferta_bloqueada "
        + "WHERE oferta_bloqueada.game_id = " + alias + ".id "
        + "AND oferta_bloqueada.url = '" + URL_TELL_ME_WHY_CHAPTER_1 + "')";
  }
}
