package com.ofertagames.backend.comum;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Chaves usadas pelo filtro "lojas preferidas" (preferencias do usuario -> catalogo). Espelha as
// mesmas lojas reconhecidas em store-brand.ts no frontend, exceto as ja excluidas globalmente por
// LojasBloqueadas (gog, humble, greenmangaming etc.) - nao faz sentido deixar o usuario "preferir"
// uma loja que o catalogo nunca mostra.
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
