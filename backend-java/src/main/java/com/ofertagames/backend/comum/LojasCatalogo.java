package com.ofertagames.backend.comum;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chaves do filtro "lojas preferidas" (Configuracoes &gt; Preferencias no frontend, parametro
 * {@code stores=} no catalogo). As chaves espelham {@code STORE_FILTER_OPTIONS} em
 * {@code services/store-brand.ts} — mexer aqui exige mexer la.
 *
 * <p>Lista conferida contra as lojas com oferta de fato ativa em producao
 * ({@code SELECT DISTINCT store_name FROM offers}, 21/08/2026), exceto as ja excluidas
 * globalmente por {@link LojasBloqueadas}: nao faz sentido deixar o usuario "preferir" uma loja
 * que o catalogo nunca mostra.
 *
 * <p>Microsoft Store fica de fora de proposito — ja e coberta pelo filtro de Plataforma (Xbox),
 * que usa o mesmo {@code store_name}/url por baixo.
 */
public final class LojasCatalogo {
  private static final Map<String, String> REGEX_POR_CHAVE = new LinkedHashMap<>();

  static {
    REGEX_POR_CHAVE.put("steam", "steam");
    REGEX_POR_CHAVE.put("epic", "epic");
    REGEX_POR_CHAVE.put("ubisoft", "ubisoft");
    REGEX_POR_CHAVE.put("ea", "\\mea\\m|electronic arts");
    REGEX_POR_CHAVE.put("battlenet", "blizzard|battle\\.?net");
    REGEX_POR_CHAVE.put("fanatical", "fanatical");
    REGEX_POR_CHAVE.put("nuuvem", "nuuvem");
    REGEX_POR_CHAVE.put("instantgaming", "instant.?gaming");
    REGEX_POR_CHAVE.put("2game", "2game");
    REGEX_POR_CHAVE.put("indiegala", "indie.?gala");
    REGEX_POR_CHAVE.put("gamersgate", "gamers.?gate");
    REGEX_POR_CHAVE.put("gamebillet", "gamebillet");
    REGEX_POR_CHAVE.put("playsum", "playsum");
    REGEX_POR_CHAVE.put("dreamgame", "dreamgame");
    REGEX_POR_CHAVE.put("zapagames", "zapagames");
    REGEX_POR_CHAVE.put("gamesload", "gamesload");
    REGEX_POR_CHAVE.put("zoomplatform", "zoom.?platform");
    REGEX_POR_CHAVE.put("fortunadigital", "fortuna.?digital");
    REGEX_POR_CHAVE.put("fireflower", "fireflower");
    REGEX_POR_CHAVE.put("etailmarket", "etail.?market");
  }

  private LojasCatalogo() {}

  public static boolean chaveValida(String chave) {
    return chave != null && REGEX_POR_CHAVE.containsKey(chave);
  }

  /**
   * Monta o regex (pra usar com {@code ~*} no Postgres) que casa qualquer uma das lojas escolhidas.
   *
   * <p>Chaves desconhecidas sao ignoradas em silencio, entao entrada invalida nunca zera o
   * resultado por engano.
   *
   * @return {@code null} quando nao ha nenhuma chave valida — e o sinal de <b>nao filtrar por
   *     loja</b> (mostrar todas), nao de "nao casar com nenhuma"
   */
  public static String regexParaChaves(List<String> chaves) {
    if (chaves == null || chaves.isEmpty()) {
      return null;
    }
    List<String> partes = chaves.stream()
        .filter(LojasCatalogo::chaveValida)
        .map(REGEX_POR_CHAVE::get)
        .toList();
    return partes.isEmpty() ? null : String.join("|", partes);
  }
}
