package com.ofertagames.backend.comum;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Chaves usadas pelo filtro "lojas preferidas" (preferencias do usuario -> catalogo). Lista
// conferida contra as lojas com ofertas de fato ativas em producao (SELECT DISTINCT store_name
// FROM offers, 21/08/2026), exceto as ja excluidas globalmente por LojasBloqueadas (gog, humble,
// greenmangaming etc.) - nao faz sentido deixar o usuario "preferir" uma loja que o catalogo nunca
// mostra. Microsoft Store fica de fora daqui de proposito: ja e coberta pelo filtro de Plataforma
// (Xbox), que usa o mesmo store_name/url por baixo.
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

  // Retorna um regex (pra usar com "~*" no Postgres) combinando as lojas escolhidas, ou null se a
  // lista estiver vazia/sem nenhuma chave reconhecida (equivale a "nao filtrar por loja").
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
