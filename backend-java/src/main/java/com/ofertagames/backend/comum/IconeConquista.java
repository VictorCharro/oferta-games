package com.ofertagames.backend.comum;

/**
 * Compacta a URL de icone de conquista da Steam guardando so a parte que varia ({@code
 * {appId}/{hash}.jpg}) e remontando na leitura.
 *
 * <p><b>Por que compactar.</b> Toda URL de icone termina em {@code /images/apps/620/WAKE_UP.jpg}, e
 * as 511 mil linhas de {@code game_achievements} comecavam com o mesmo prefixo de 65 caracteres —
 * 32 MB so de texto repetido, num banco que estourou a cota de 0,5 GB do plano free do Supabase.
 *
 * <p><b>Por que o prefixo de leitura e diferente do que a Steam manda (12/09/2026).</b> O
 * {@code GetSchemaForGame} ainda devolve o caminho legado
 * ({@code steamcdn-a.akamaihd.net/steamcommunity/public/images/apps/}), mas a Steam migrou os
 * icones pra {@code shared.*.steamstatic.com/community_assets/images/apps/}. O caminho legado
 * continua servindo arte antiga, porem arte **nova** so existe no caminho novo: conquista recem
 * criada respondia 404 e aparecia sem icone no perfil (caso real: as conquistas novas de Dead by
 * Daylight). Como o que fica gravado e so {@code {appId}/{hash}.jpg}, trocar o prefixo de leitura
 * conserta as 511 mil linhas de uma vez, sem migration — verificado que o caminho novo serve tanto
 * os hashes antigos quanto os novos.
 *
 * <p>A conversao <b>nao assume</b> que o formato se mantem: valor que nao casa com nenhum prefixo
 * conhecido e guardado inteiro, e valor ja absoluto e devolvido como veio.
 */
public final class IconeConquista {
  /** Caminho usado pra servir. E o mesmo que a pagina de conquistas da Steam usa hoje. */
  private static final String PREFIXO_ATUAL =
      "https://shared.fastly.steamstatic.com/community_assets/images/apps/";

  /**
   * Prefixos que podem aparecer no que a Steam devolve, todos equivalentes pro nosso uso. O legado
   * vem primeiro porque e o que o {@code GetSchemaForGame} manda hoje.
   */
  private static final String[] PREFIXOS_CONHECIDOS = {
    "https://steamcdn-a.akamaihd.net/steamcommunity/public/images/apps/",
    "https://cdn.akamai.steamstatic.com/steamcommunity/public/images/apps/",
    "https://cdn.cloudflare.steamstatic.com/steamcommunity/public/images/apps/",
    PREFIXO_ATUAL,
    "https://shared.akamai.steamstatic.com/community_assets/images/apps/",
    "https://shared.cloudflare.steamstatic.com/community_assets/images/apps/",
  };

  private IconeConquista() {}

  /** Forma de guardar: tira o prefixo, seja o legado ou o atual; qualquer outra coisa vai inteira. */
  public static String compactar(String url) {
    if (url == null) {
      return null;
    }
    for (String prefixo : PREFIXOS_CONHECIDOS) {
      if (url.startsWith(prefixo)) {
        return url.substring(prefixo.length());
      }
    }
    return url;
  }

  /** Forma de servir: recoloca o prefixo atual, a menos que o valor gravado ja seja absoluto. */
  public static String expandir(String guardado) {
    if (guardado == null || guardado.isBlank()) {
      return guardado;
    }
    return guardado.startsWith("http") ? guardado : PREFIXO_ATUAL + guardado;
  }
}
