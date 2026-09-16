package com.ofertagames.backend.comum;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Siglas que as pessoas digitam na busca e nao aparecem no titulo do jogo (16/09/2026).
 *
 * <p>"gta" nao existe em "Grand Theft Auto", entao a busca por titulo devolvia GTA+ e, pior,
 * "RagTag" — que tem "gta" no meio da palavra. Aqui a sigla vira tambem o nome por extenso, e o
 * casamento passa a ser por <b>inicio de palavra</b> ({@code \m} no regex do Postgres), o que tira
 * o "RagTag" do caminho sem exigir o titulo exato.
 *
 * <p>Vale so pro termo inteiro ("gta", "gta 6"), nao pra pedaco solto: "cod" dentro de "codex" nao
 * vira Call of Duty.
 */
public final class ApelidosBusca {
  private static final Map<String, List<String>> APELIDOS = Map.ofEntries(
      Map.entry("gta", List.of("grand theft auto")),
      Map.entry("cod", List.of("call of duty")),
      Map.entry("mw", List.of("modern warfare")),
      Map.entry("bo", List.of("black ops")),
      Map.entry("ac", List.of("assassin's creed", "assassins creed")),
      Map.entry("re", List.of("resident evil")),
      Map.entry("rdr", List.of("red dead redemption")),
      Map.entry("tlou", List.of("the last of us")),
      Map.entry("gow", List.of("god of war", "gears of war")),
      Map.entry("cs", List.of("counter-strike", "counter strike")),
      Map.entry("csgo", List.of("counter-strike", "counter strike")),
      Map.entry("bf", List.of("battlefield")),
      Map.entry("nfs", List.of("need for speed")),
      Map.entry("pes", List.of("efootball", "pro evolution soccer")),
      Map.entry("fifa", List.of("ea sports fc", "fifa")),
      Map.entry("dbd", List.of("dead by daylight")),
      Map.entry("poe", List.of("path of exile")),
      Map.entry("wow", List.of("world of warcraft")),
      Map.entry("lol", List.of("league of legends")),
      Map.entry("tf2", List.of("team fortress 2")),
      Map.entry("gtav", List.of("grand theft auto v")),
      Map.entry("gtavi", List.of("grand theft auto vi")),
      Map.entry("botw", List.of("breath of the wild")),
      Map.entry("mgs", List.of("metal gear solid")),
      Map.entry("ff", List.of("final fantasy")),
      Map.entry("dmc", List.of("devil may cry")),
      Map.entry("kcd", List.of("kingdom come deliverance")),
      Map.entry("sf", List.of("street fighter")),
      Map.entry("mk", List.of("mortal kombat")),
      Map.entry("hl", List.of("half-life", "half life")),
      Map.entry("elden", List.of("elden ring")),
      Map.entry("silksong", List.of("hollow knight: silksong")));

  private ApelidosBusca() {}

  /**
   * O termo digitado mais as variacoes conhecidas. A sigla pode vir com o resto do nome ("gta 6",
   * "re 4"): nesse caso o complemento e mantido ("grand theft auto 6", "resident evil 4").
   */
  public static List<String> expandir(String termo) {
    String limpo = termo == null ? "" : termo.trim().toLowerCase(Locale.ROOT);
    List<String> termos = new ArrayList<>();
    if (limpo.isEmpty()) return termos;
    termos.add(limpo);

    String[] partes = limpo.split("\\s+", 2);
    List<String> expansoes = APELIDOS.get(partes[0]);
    if (expansoes != null) {
      String complemento = partes.length > 1 ? " " + partes[1] : "";
      for (String expansao : expansoes) termos.add(expansao + complemento);
    }
    return termos;
  }

  /**
   * Regex do Postgres ({@code ~*}) que casa o termo em <b>inicio de palavra</b>, com os
   * metacaracteres do que a pessoa digitou escapados.
   */
  public static String regexInicioDePalavra(String termo) {
    return "\\m" + termo.replaceAll("([\\\\.^$|()\\[\\]{}*+?])", "\\\\$1");
  }
}
