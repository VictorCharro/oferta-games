package com.ofertagames.backend.comum;

/**
 * Compacta a URL de icone de conquista da Steam guardando so a parte que varia.
 *
 * <p>Toda URL de icone tem a forma
 * {@code https://steamcdn-a.akamaihd.net/steamcommunity/public/images/apps/620/WAKE_UP.jpg}, e as
 * 510 mil linhas de {@code game_achievements} comecavam com esse mesmo prefixo de 65 caracteres —
 * 32 MB so de texto repetido, num banco que estourou a cota de 0,5 GB do plano free do Supabase.
 * Guardando apenas {@code 620/WAKE_UP.jpg} e remontando na leitura, o conteudo servido e identico.
 *
 * <p>A conversao <b>nao assume</b> que o formato se mantem: valor que nao casa com o prefixo e
 * guardado inteiro, e valor ja absoluto e devolvido como veio. Se a Steam trocar de CDN, entra o
 * formato novo sem migration e sem quebrar o que ja esta gravado.
 */
public final class IconeConquista {
  private static final String PREFIXO = "https://steamcdn-a.akamaihd.net/steamcommunity/public/images/apps/";

  private IconeConquista() {}

  /** Forma de guardar: tira o prefixo padrao; qualquer outra coisa vai inteira. */
  public static String compactar(String url) {
    if (url == null) {
      return null;
    }
    return url.startsWith(PREFIXO) ? url.substring(PREFIXO.length()) : url;
  }

  /** Forma de servir: recoloca o prefixo, a menos que o valor gravado ja seja uma URL completa. */
  public static String expandir(String guardado) {
    if (guardado == null || guardado.isBlank()) {
      return guardado;
    }
    return guardado.startsWith("http") ? guardado : PREFIXO + guardado;
  }
}
