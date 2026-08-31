package com.ofertagames.backend.comum;

/**
 * Itens especificos escondidos do catalogo por terem link quebrado — hoje so
 * "Tell Me Why: Chapter 1".
 *
 * <p>Excecao pontual, nao regra geral: bloqueio por loja fica em {@link LojasBloqueadas} e por
 * tipo de conteudo em {@link ConteudosNaoJogos}.
 */
public final class JogosBloqueados {
  private static final String SLUG_TELL_ME_WHY_CHAPTER_1 = "tell-me-why-chapter-1";
  private static final String URL_TELL_ME_WHY_CHAPTER_1 = "https://itad.link/019e8518-404a-709b-ae47-a4ef949552ea/";

  private JogosBloqueados() {}

  public static boolean contemSlug(String slug) {
    return SLUG_TELL_ME_WHY_CHAPTER_1.equals(slug);
  }

  /**
   * Bloqueia por slug <b>e</b> pela URL ITAD da oferta: a mesma entrada quebrada ja reapareceu no
   * catalogo com outro slug, entao filtrar so pelo slug deixava passar.
   *
   * <p>Fragmento comeca com {@code AND}, pra concatenar em WHERE ja existente. O {@code alias}
   * precisa ser literal do codigo (interpolado direto no SQL) e deve apontar pra {@code games} —
   * a subquery usa {@code alias.id} contra {@code offers.game_id}.
   */
  public static String filtroSql(String alias) {
    return "AND " + alias + ".slug <> '" + SLUG_TELL_ME_WHY_CHAPTER_1 + "'"
        + " AND NOT EXISTS ("
        + "SELECT 1 FROM offers oferta_bloqueada "
        + "WHERE oferta_bloqueada.game_id = " + alias + ".id "
        + "AND oferta_bloqueada.url = '" + URL_TELL_ME_WHY_CHAPTER_1 + "')";
  }
}
