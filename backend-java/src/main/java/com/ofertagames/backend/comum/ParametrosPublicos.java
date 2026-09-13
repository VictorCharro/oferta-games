package com.ofertagames.backend.comum;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Normaliza os parametros das rotas publicas de catalogo e descontos <b>antes</b> de virarem chave
 * de cache.
 *
 * <p>O {@code @Cacheable} usa todos os argumentos como chave. Enquanto eles chegavam crus da URL,
 * cada variacao irrelevante ({@code size=24}, {@code sort=xyz}, {@code stores=steam,steam}) era uma
 * entrada nova e pagava a consulta inteira: {@code /api/deals/top?size=24} levava 13s contra 0,04s
 * do cacheado, e um script variando {@code size} de 1 a 200 derrubava o unico vCPU da VM sem
 * precisar de login (issue #16). Aqui todo valor desconhecido cai no padrao, entao o numero de
 * chaves possiveis fica pequeno e previsivel.
 *
 * <p>Os valores aceitos espelham o que o frontend manda de fato. Mexeu la, mexe aqui — senao a
 * opcao nova e silenciosamente trocada pelo padrao.
 */
public final class ParametrosPublicos {
  /** Ranking de descontos: calculado sempre com este tamanho e fatiado depois (ver ControladorDescontos). */
  public static final int TAMANHO_TOPO_DESCONTOS = 200;

  private static final Set<String> ORDENACOES_DESCONTOS = Set.of("discount", "rank");
  private static final Set<String> ORDENACOES_CATALOGO = Set.of("rank", "popularity", "discount", "price_asc", "price_desc");
  private static final Set<String> TIPOS = Set.of("all", "game", "dlc");
  private static final Set<String> PLATAFORMAS = Set.of("all", "pc", "xbox", "playstation");

  /** catalog.ts e home.ts usam 20; best-sellers.ts usa 40. */
  private static final Set<Integer> TAMANHOS_CATALOGO = Set.of(20, 40);
  private static final int TAMANHO_CATALOGO_PADRAO = 20;

  /**
   * 200 paginas de 20 = 4.000 jogos de rolagem. Ninguem chega la rolando; alem disso, pagina funda
   * so serve pra varrer o catalogo, e o sitemap ja entrega todos os slugs.
   */
  static final int PAGINA_MAXIMA = 200;

  /** Acima disso nao existe jogo no catalogo; tratar como "sem limite" em vez de chave nova. */
  static final double PRECO_MAXIMO_UTIL = 2_000;

  static final int TAMANHO_MAXIMO_BUSCA = 80;
  static final int LOJAS_MAXIMAS = 20;

  private ParametrosPublicos() {}

  public static String ordenacaoDescontos(String valor) {
    return escolher(valor, ORDENACOES_DESCONTOS, "discount");
  }

  public static String ordenacaoCatalogo(String valor) {
    return escolher(valor, ORDENACOES_CATALOGO, "rank");
  }

  public static String tipo(String valor) {
    return escolher(valor, TIPOS, "all");
  }

  public static String plataforma(String valor) {
    return escolher(valor, PLATAFORMAS, "all");
  }

  public static int tamanhoDescontos(int valor) {
    return Math.min(TAMANHO_TOPO_DESCONTOS, Math.max(1, valor));
  }

  /** Tamanho fora da lista vira o padrao, nao o mais proximo: e o que mantem as chaves finitas. */
  public static int tamanhoCatalogo(int valor) {
    return TAMANHOS_CATALOGO.contains(valor) ? valor : TAMANHO_CATALOGO_PADRAO;
  }

  public static int pagina(int valor) {
    return Math.min(PAGINA_MAXIMA, Math.max(0, valor));
  }

  /**
   * Preco minimo arredondado pra baixo em reais inteiros. Ninguem filtra por centavo, e cada
   * centavo seria uma chave de cache nova. Arredondar pra baixo so inclui mais jogos, nunca esconde.
   */
  public static Double precoMinimo(Double valor) {
    if (valor == null || valor.isNaN() || valor <= 0) return null;
    if (valor >= PRECO_MAXIMO_UTIL) return PRECO_MAXIMO_UTIL;
    return Math.floor(valor);
  }

  /** Preco maximo arredondado pra cima (pelo mesmo motivo); acima do teto util vira "sem limite". */
  public static Double precoMaximo(Double valor) {
    if (valor == null || valor.isNaN() || valor < 0 || valor >= PRECO_MAXIMO_UTIL) return null;
    return Math.ceil(valor);
  }

  /** Desconto minimo inteiro de 1 a 100; 0 ou invalido nao filtra. */
  public static Double descontoMinimo(Double valor) {
    if (valor == null || valor.isNaN() || valor < 1) return null;
    return (double) Math.min(100, Math.round(valor));
  }

  /**
   * Busca com espacos colapsados e em minusculas. O filtro e {@code ILIKE}, entao caixa nao muda o
   * resultado — so criaria chaves duplicadas. Vazio vira {@code null} (sem filtro).
   */
  public static String busca(String valor) {
    if (valor == null) return null;
    String limpo = valor.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    if (limpo.isEmpty()) return null;
    return limpo.length() > TAMANHO_MAXIMO_BUSCA ? limpo.substring(0, TAMANHO_MAXIMO_BUSCA) : limpo;
  }

  /** So chaves conhecidas, sem repeticao e em ordem fixa: {@code epic,steam} e {@code steam,epic} sao a mesma chave. */
  public static List<String> lojas(String valor) {
    if (valor == null || valor.isBlank()) return List.of();
    return java.util.Arrays.stream(valor.split(","))
        .map(s -> s.trim().toLowerCase(Locale.ROOT))
        .filter(LojasCatalogo::chaveValida)
        .distinct()
        .sorted()
        .limit(LOJAS_MAXIMAS)
        .toList();
  }

  private static String escolher(String valor, Set<String> aceitos, String padrao) {
    if (valor == null) return padrao;
    String normalizado = valor.trim().toLowerCase(Locale.ROOT);
    return aceitos.contains(normalizado) ? normalizado : padrao;
  }
}
