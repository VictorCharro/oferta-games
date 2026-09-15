package com.ofertagames.backend.steam;

import com.ofertagames.backend.comum.ClassificadorDlc;
import com.ofertagames.backend.comum.ConfiguracaoCache;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Cliente das APIs publicas da Steam usadas para enriquecer o catalogo: {@code appdetails}
 * (capa, descricao, generos, midia, requisitos, DLCs), {@code appreviews} (resumo e avaliacoes) e
 * {@code ISteamUserStats} (esquema de conquistas e percentuais globais).
 *
 * <p>Sao APIs de duas naturezas: as da <b>loja</b> ({@code store.steampowered.com}) sao publicas e
 * nao pedem chave; as de <b>stats</b> ({@code api.steampowered.com}) exigem
 * {@code STEAM_WEB_API_KEY}. Sem a chave, so o esquema de conquistas para de funcionar.
 *
 * <p>Nenhum metodo propaga excecao: falha de rede ou resposta fora do formato vira
 * {@link Optional#empty()} ou lista vazia. E deliberado, porque quem chama sao jobs de lote em que
 * um jogo problematico nao pode derrubar a rodada — mas significa que <b>nao da pra distinguir
 * "nao existe" de "falhou"</b>.
 *
 * <p>Tudo e pedido em pt-BR ({@code l=brazilian}, {@code cc=br}). Esquecer esse parametro ja
 * causou o catalogo inteiro de conquistas ser gravado em ingles (corrigido em 19/07/2026, exigiu
 * reprocessar a tabela).
 */
@Service
public class ServicoSteam {
  /**
   * Extrai o app id da URL da loja.
   *
   * <p>O grupo {@code (?:agecheck/)?} e essencial: jogos com aviso de conteudo (violencia/nudez)
   * redirecionam para {@code /agecheck/app/<id>/} em vez de {@code /app/<id>/}. Sem ele o appId
   * nunca era extraido e o jogo ficava permanentemente sem {@code steam_app_id}, o que bloqueia
   * reviews, detalhes e conquistas. Corrigido em 31/07/2026 — o fix liberou centenas de jogos de
   * uma vez e dobrou o backlog do job de conquistas.
   */
  private static final Pattern APP_ID = Pattern.compile("store\\.steampowered\\.com/(?:agecheck/)?app/(\\d+)");

  private final RestClient restClient;
  private final HttpClient clienteHttp;
  private final String chaveApi;

  ServicoSteam(RestClient.Builder restClientBuilder, @Value("${app.steam.api-key}") String chaveApi) {
    this.restClient = restClientBuilder.build();
    this.clienteHttp = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build();
    this.chaveApi = chaveApi;
  }

  public boolean tituloPareceDlc(String titulo) {
    return ClassificadorDlc.pareceDlc(titulo);
  }

  /**
   * Descobre o app id da Steam a partir da URL de uma oferta.
   *
   * <p>Faz uma requisicao <b>seguindo redirecionamentos</b> e le o app id da URL final, e nao da
   * original: as URLs que a ITAD entrega costumam ser links de redirecionamento
   * ({@code itad.link/...}), que so revelam o destino depois de seguidos. Se a requisicao falhar,
   * tenta extrair da URL original como ultimo recurso.
   *
   * <p>Usa {@code BodyHandlers.discarding()} — interessa so a URL final, nao o HTML.
   *
   * @return vazio quando a URL (original ou final) nao aponta pra uma pagina de app da Steam, o
   *     que e o caso normal de ofertas de outras lojas
   */
  public Optional<String> resolverAppIdSteam(String urlOferta) {
    try {
      HttpRequest requisicao = HttpRequest.newBuilder(URI.create(urlOferta)).GET().build();
      HttpResponse<Void> resposta = clienteHttp.send(requisicao, HttpResponse.BodyHandlers.discarding());
      return extrairAppIdSteam(resposta.uri().toString());
    } catch (Exception ignored) {
      return extrairAppIdSteam(urlOferta);
    }
  }

  /**
   * Chamada {@code appdetails} da loja — a fonte de quase tudo que o catalogo mostra sobre um jogo.
   *
   * <p>Uma unica chamada alimenta dois jobs diferentes (metadados e detalhes), por isso ela
   * devolve muito mais campo do que qualquer chamador usa sozinho.
   *
   * <p>Cuidado com {@code ehDlc}: vem de {@code type == "dlc"}, e a Steam classifica trilha sonora
   * como {@code "music"}. Quem consome precisa combinar com a heuristica de titulo
   * ({@link #tituloPareceDlc}) — e o que {@code ServicoCatalogo} faz.
   *
   * @return vazio quando a Steam responde {@code success: false}, o que acontece com app id
   *     inexistente, removido ou restrito por regiao
   */
  private static final Pattern APP_ID_NO_LOGO = Pattern.compile("/apps/(\\d+)/");

  /**
   * App ids de uma lista da busca da loja, na ordem da Steam: {@code topsellers} (mais vendidos),
   * {@code popularnew} (lancamentos populares) ou {@code popularcomingsoon} (pre-venda popular).
   * So jogos ({@code category1=998}), sem DLC, trilha sonora ou software.
   *
   * <p>E a fonte da descoberta de jogos novos: a ITAD nao tem lista de lancamentos, e o catalogo so
   * crescia pela busca do site. O JSON dessa pagina nao traz o app id em campo proprio; ele vem na
   * URL da imagem ({@code .../apps/<id>/...}). Pacotes e bundles nao tem esse formato e ficam de fora.
   */
  public List<Integer> listarAppsDaBusca(String filtro, int quantidade) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> resposta = restClient.get()
          .uri("https://store.steampowered.com/search/results/?filter={filtro}&json=1&start=0&count={quantidade}&cc=br&l=brazilian&category1=998",
              filtro, quantidade)
          .retrieve()
          .body(Map.class);
      if (resposta == null || !(resposta.get("items") instanceof List<?> itens)) return List.of();
      List<Integer> ids = new ArrayList<>();
      for (Object item : itens) {
        if (!(item instanceof Map<?, ?> mapa)) continue;
        var achado = APP_ID_NO_LOGO.matcher(String.valueOf(mapa.get("logo")));
        if (achado.find()) ids.add(Integer.parseInt(achado.group(1)));
      }
      return ids;
    } catch (Exception falha) {
      return List.of();
    }
  }

  public Optional<DetalhesAplicativoSteam> buscarDetalhesAplicativo(String appId) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> resposta = restClient.get()
          .uri("https://store.steampowered.com/api/appdetails?appids={appId}&l=brazilian&cc=br", appId)
          .retrieve()
          .body(Map.class);
      if (resposta == null) {
        return Optional.empty();
      }

      Object entradaObjeto = resposta.get(appId);
      if (!(entradaObjeto instanceof Map<?, ?> entrada) || !Boolean.TRUE.equals(entrada.get("success"))) {
        return Optional.empty();
      }
      Object dadosObjeto = entrada.get("data");
      if (!(dadosObjeto instanceof Map<?, ?> dados)) {
        return Optional.empty();
      }

      boolean ehDlc = "dlc".equals(dados.get("type"));
      String imagemCabecalho = comoTexto(dados.get("header_image"));
      String descricaoCurta = comoTexto(dados.get("short_description"));
      String dataLancamento = comoDataLancamento(dados.get("release_date"));
      List<String> generos = comoListaDeCampo(dados.get("genres"), "description");
      List<String> desenvolvedores = comoListaDeTexto(dados.get("developers"));
      List<String> publicadoras = comoListaDeTexto(dados.get("publishers"));
      List<String> screenshots = comoListaDeCampo(dados.get("screenshots"), "path_full");
      List<String> categorias = comoListaDeCampo(dados.get("categories"), "description");

      Map<?, ?> trailer = primeiroTrailer(dados.get("movies"));
      String trailerUrl = trailer == null ? null : comoUrlTrailer(trailer);
      String trailerThumbnail = trailer == null ? null : comoTexto(trailer.get("thumbnail"));

      SobreParseado sobre = parsearSobre(comoTexto(dados.get("about_the_game")));

      Map<?, ?> requisitos = comoMapa(dados.get("pc_requirements"));
      String requisitosMinimos = requisitos == null ? null : comoRequisitos(requisitos.get("minimum"));
      String requisitosRecomendados = requisitos == null ? null : comoRequisitos(requisitos.get("recommended"));

      List<Integer> dlcAppIds = comoListaDeInteiros(dados.get("dlc"));

      return Optional.of(new DetalhesAplicativoSteam(
          ehDlc, imagemCabecalho, descricaoCurta, generos, desenvolvedores, publicadoras, dataLancamento, screenshots,
          trailerUrl, trailerThumbnail, sobre.texto(), sobre.destaques(), categorias, requisitosMinimos, requisitosRecomendados,
          dlcAppIds));
    } catch (RuntimeException ignored) {
      return Optional.empty();
    }
  }

  /**
   * Resumo agregado das reviews (rotulo tipo "Muito positivas" e totais de positivas/negativas).
   *
   * <p>So o resumo — o texto das avaliacoes vem de {@code buscarAvaliacoes}, que e paginado e
   * cacheado a parte. Consulta {@code language=all} de proposito: o resumo deve refletir a
   * recepcao global do jogo, nao so a de quem escreveu em portugues.
   */
  public Optional<ReviewsSteam> buscarReviews(String appId) {
    try {
      Map<String, Object> resposta = restClient.get()
          .uri("https://store.steampowered.com/appreviews/{appId}?json=1&language=all&purchase_type=all", appId)
          .retrieve()
          .body(new ParameterizedTypeReference<Map<String, Object>>() {});
      Map<?, ?> sumario = resposta == null ? null : comoMapa(resposta.get("query_summary"));
      if (sumario == null) {
        return Optional.empty();
      }
      return Optional.of(new ReviewsSteam(
          comoTexto(sumario.get("review_score_desc")),
          comoInteiro(sumario.get("total_positive")),
          comoInteiro(sumario.get("total_negative"))));
    } catch (RuntimeException ignored) {
      return Optional.empty();
    }
  }

  @Cacheable(
      cacheNames = ConfiguracaoCache.CACHE_AVALIACOES_STEAM,
      key = "#appId + ':' + #cursor + ':' + #ordenacao + ':' + #idioma")
  public RespostaAvaliacoesSteam buscarAvaliacoes(
      String appId,
      String cursor,
      String ordenacao,
      String idioma
  ) {
    String filtro = normalizarOrdenacao(ordenacao);
    String idiomaConsulta = normalizarIdioma(idioma);
    PaginaAvaliacoes pagina;

    if ((cursor == null || cursor.isBlank()) && "brazilian".equals(idiomaConsulta)) {
      PaginaAvaliacoes brasileiras = buscarPaginaAvaliacoes(appId, "brazilian", filtro, "*", 10);
      if (brasileiras.avaliacoes().size() >= 10) {
        pagina = brasileiras;
      } else {
        int faltantes = 10 - brasileiras.avaliacoes().size();
        PaginaAvaliacoes outras = buscarPaginaAvaliacoes(appId, "all", filtro, "*", faltantes);
        Map<String, Map<?, ?>> unicas = new LinkedHashMap<>();
        brasileiras.avaliacoes().forEach(item ->
            unicas.put(comoTexto(item.get("recommendationid")), item));
        outras.avaliacoes().forEach(item ->
            unicas.putIfAbsent(comoTexto(item.get("recommendationid")), item));
        pagina = new PaginaAvaliacoes(new ArrayList<>(unicas.values()), outras.proximoCursor());
        idiomaConsulta = "all";
      }
    } else {
      pagina = buscarPaginaAvaliacoes(
          appId,
          idiomaConsulta,
          filtro,
          cursor == null || cursor.isBlank() ? "*" : cursor,
          10);
      if (pagina.avaliacoes().isEmpty()
          && "brazilian".equals(idiomaConsulta)
          && cursor != null
          && !cursor.isBlank()) {
        pagina = buscarPaginaAvaliacoes(appId, "all", filtro, "*", 10);
        idiomaConsulta = "all";
      }
    }

    List<String> autoresIds = pagina.avaliacoes().stream()
        .map(entrada -> comoMapa(entrada.get("author")))
        .map(autor -> autor == null ? null : comoTexto(autor.get("steamid")))
        .filter(id -> id != null && !id.isBlank())
        .distinct()
        .toList();
    Map<String, PerfilAutorSteam> autores = buscarAutores(autoresIds);

    List<AvaliacaoSteam> avaliacoes = new ArrayList<>();
    for (Map<?, ?> entrada : pagina.avaliacoes()) {
      Map<?, ?> autor = comoMapa(entrada.get("author"));
      String autorId = autor == null ? null : comoTexto(autor.get("steamid"));
      PerfilAutorSteam perfil = autores.get(autorId);
      Integer criadaEm = comoInteiro(entrada.get("timestamp_created"));
      avaliacoes.add(new AvaliacaoSteam(
          comoTexto(entrada.get("recommendationid")),
          autorId,
          perfil == null ? "Jogador Steam" : perfil.nome(),
          perfil == null ? null : perfil.avatarUrl(),
          comoTexto(entrada.get("review")),
          Boolean.TRUE.equals(entrada.get("voted_up")),
          comoInteiro(entrada.get("votes_up")),
          comoInteiro(entrada.get("votes_funny")),
          comoInteiro(entrada.get("comment_count")),
          autor == null ? null : comoInteiro(autor.get("playtime_forever")),
          criadaEm == null ? null : Instant.ofEpochSecond(criadaEm.longValue()).toString(),
          comoTexto(entrada.get("language"))));
    }
    String proximoCursor = pagina.proximoCursor();
    boolean temMais = !avaliacoes.isEmpty()
        && proximoCursor != null
        && !proximoCursor.isBlank()
        && !proximoCursor.equals(cursor);
    return new RespostaAvaliacoesSteam(
        appId,
        avaliacoes,
        proximoCursor,
        temMais,
        idiomaConsulta,
        filtro);
  }

  private PaginaAvaliacoes buscarPaginaAvaliacoes(
      String appId,
      String idioma,
      String filtro,
      String cursor,
      int quantidade
  ) {
    try {
      Map<String, Object> resposta = restClient.get()
          .uri(uri -> uri
              .scheme("https").host("store.steampowered.com")
              .path("/appreviews/{appId}")
              .queryParam("json", 1)
              .queryParam("filter", filtro)
              .queryParam("language", idioma)
              .queryParam("review_type", "all")
              .queryParam("purchase_type", "all")
              .queryParam("day_range", 365)
              .queryParam("cursor", cursor)
              .queryParam("num_per_page", quantidade)
              .build(appId))
          .retrieve()
          .body(new ParameterizedTypeReference<Map<String, Object>>() {});
      List<Map<?, ?>> resultado = new ArrayList<>();
      for (Object item : resposta == null ? List.of() : comoLista(resposta.get("reviews"))) {
        Map<?, ?> avaliacao = comoMapa(item);
        if (avaliacao != null) resultado.add(avaliacao);
      }
      return new PaginaAvaliacoes(resultado, comoTexto(resposta == null ? null : resposta.get("cursor")));
    } catch (RuntimeException ignorado) {
      return new PaginaAvaliacoes(List.of(), null);
    }
  }

  private String normalizarOrdenacao(String ordenacao) {
    return switch (ordenacao == null ? "" : ordenacao.toLowerCase()) {
      case "all", "updated" -> ordenacao.toLowerCase();
      default -> "recent";
    };
  }

  private String normalizarIdioma(String idioma) {
    return "all".equalsIgnoreCase(idioma) ? "all" : "brazilian";
  }

  private Map<String, PerfilAutorSteam> buscarAutores(List<String> autoresIds) {
    if (chaveApi == null || chaveApi.isBlank() || autoresIds.isEmpty()) return Map.of();
    try {
      Map<String, Object> resposta = restClient.get()
          .uri(uri -> uri
              .scheme("https").host("api.steampowered.com")
              .path("/ISteamUser/GetPlayerSummaries/v2/")
              .queryParam("key", chaveApi)
              .queryParam("steamids", String.join(",", autoresIds))
              .build())
          .retrieve()
          .body(new ParameterizedTypeReference<Map<String, Object>>() {});
      Map<?, ?> corpo = resposta == null ? null : comoMapa(resposta.get("response"));
      Map<String, PerfilAutorSteam> autores = new LinkedHashMap<>();
      for (Object item : corpo == null ? List.of() : comoLista(corpo.get("players"))) {
        Map<?, ?> jogador = comoMapa(item);
        if (jogador == null) continue;
        String id = comoTexto(jogador.get("steamid"));
        if (id != null) {
          autores.put(id, new PerfilAutorSteam(
              comoTexto(jogador.get("personaname")),
              comoTexto(jogador.get("avatarfull"))));
        }
      }
      return autores;
    } catch (RuntimeException ignorado) {
      return Map.of();
    }
  }

  /**
   * Catalogo de conquistas do jogo (nome, titulo, descricao e icones), em pt-BR.
   *
   * <p>Exige {@code STEAM_WEB_API_KEY}: sem chave devolve lista vazia sem nem tentar a chamada.
   *
   * <p><b>Lista vazia e ambigua</b> e o chamador precisa tratar: pode ser jogo que realmente nao
   * tem conquista, chave ausente, ou falha de rede. {@code ServicoCatalogo.processarConquistas}
   * resolve marcando o jogo como verificado de qualquer jeito, pra ele nao voltar pra fila pra
   * sempre.
   *
   * <p>O {@code l=brazilian} nao e opcional: sem ele a Steam devolve tudo em ingles, o que ja
   * obrigou a zerar e reprocessar a tabela inteira de conquistas.
   */
  public List<ConquistaEsquemaSteam> buscarEsquemaConquistas(String appId) {
    if (chaveApi == null || chaveApi.isBlank()) {
      return List.of();
    }
    try {
      Map<String, Object> resposta = restClient.get()
          .uri(uri -> uri
              .scheme("https").host("api.steampowered.com")
              .path("/ISteamUserStats/GetSchemaForGame/v2/")
              .queryParam("key", chaveApi)
              .queryParam("appid", appId)
              .queryParam("l", "brazilian")
              .build())
          .retrieve()
          .body(new ParameterizedTypeReference<Map<String, Object>>() {});
      Map<?, ?> jogo = resposta == null ? null : comoMapa(resposta.get("game"));
      Map<?, ?> estatisticasDisponiveis = jogo == null ? null : comoMapa(jogo.get("availableGameStats"));
      List<?> conquistas = estatisticasDisponiveis == null ? List.of() : comoLista(estatisticasDisponiveis.get("achievements"));

      List<ConquistaEsquemaSteam> resultado = new ArrayList<>();
      for (Object item : conquistas) {
        Map<?, ?> conquista = comoMapa(item);
        if (conquista == null) continue;
        String nome = comoTexto(conquista.get("name"));
        if (nome == null || nome.isBlank()) continue;
        resultado.add(new ConquistaEsquemaSteam(
            nome,
            comoTexto(conquista.get("displayName")),
            comoTexto(conquista.get("description")),
            comoTexto(conquista.get("icon")),
            comoTexto(conquista.get("icongray"))));
      }
      return resultado;
    } catch (RuntimeException ignored) {
      return List.of();
    }
  }

  /**
   * Percentual global de jogadores que desbloqueou cada conquista, chaveado pelo
   * {@code api_name} — a mesma chave do esquema, para mesclar as duas respostas.
   *
   * <p>Endpoint publico: funciona mesmo sem {@code STEAM_WEB_API_KEY}, ao contrario de
   * {@link #buscarEsquemaConquistas}.
   *
   * @return mapa vazio quando o jogo nao tem estatistica publica; conquista ausente do mapa
   *     simplesmente fica sem percentual
   */
  public Map<String, Double> buscarPercentuaisGlobais(String appId) {
    try {
      Map<String, Object> resposta = restClient.get()
          .uri("https://api.steampowered.com/ISteamUserStats/GetGlobalAchievementPercentagesForApp/v2/?gameid={appId}", appId)
          .retrieve()
          .body(new ParameterizedTypeReference<Map<String, Object>>() {});
      Map<?, ?> achievementpercentages = resposta == null ? null : comoMapa(resposta.get("achievementpercentages"));
      List<?> conquistas = achievementpercentages == null ? List.of() : comoLista(achievementpercentages.get("achievements"));

      Map<String, Double> percentuais = new java.util.LinkedHashMap<>();
      for (Object item : conquistas) {
        Map<?, ?> conquista = comoMapa(item);
        if (conquista == null) continue;
        String nome = comoTexto(conquista.get("name"));
        Double percentual = comoDouble(conquista.get("percent"));
        if (nome == null || percentual == null) continue;
        percentuais.put(nome, percentual);
      }
      return percentuais;
    } catch (RuntimeException ignored) {
      return Map.of();
    }
  }

  private Optional<String> extrairAppIdSteam(String url) {
    var matcher = APP_ID.matcher(url);
    return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
  }

  private static Map<?, ?> primeiroTrailer(Object valor) {
    List<?> filmes = comoLista(valor);
    return filmes.isEmpty() ? null : comoMapa(filmes.get(0));
  }

  private static String comoUrlTrailer(Map<?, ?> trailer) {
    String url = comoTexto(trailer.get("hls_h264"));
    return url != null ? url : comoTexto(trailer.get("dash_h264"));
  }

  /**
   * Quebra o {@code about_the_game} (HTML) em texto corrido + destaques.
   *
   * <p>A Steam manda tudo num HTML unico com blocos {@code <h2 class="bb_tag">Titulo</h2>} seguidos
   * do conteudo. A convencao adotada: o texto <b>antes</b> do primeiro titulo vira a descricao
   * completa, e cada bloco a partir dali vira um destaque com titulo proprio.
   *
   * <p>Como depende do markup deles, jogo sem nenhum {@code bb_tag} sai com zero destaques e so o
   * texto — situacao normal, nao erro. E o motivo de a aba "Sobre" so aparecer quando ha destaques
   * ou trailer.
   */
  private static SobreParseado parsearSobre(String html) {
    if (html == null || html.isBlank()) {
      return new SobreParseado(null, List.of());
    }
    Document doc = Jsoup.parseBodyFragment(html);
    List<DetalhesAplicativoSteam.DestaqueSteam> destaques = new ArrayList<>();
    StringBuilder intro = new StringBuilder();
    StringBuilder atual = new StringBuilder();
    String tituloAtual = null;

    for (Node node : doc.body().childNodes()) {
      if (node instanceof Element el && "h2".equals(el.tagName()) && el.hasClass("bb_tag")) {
        fecharSecao(tituloAtual, atual, intro, destaques);
        tituloAtual = el.text().trim();
        atual = new StringBuilder();
      } else {
        atual.append(textoDoNode(node)).append(' ');
      }
    }
    fecharSecao(tituloAtual, atual, intro, destaques);

    String texto = limparEspacos(intro.toString());
    return new SobreParseado(texto.isBlank() ? null : texto, destaques);
  }

  private static void fecharSecao(
      String titulo, StringBuilder buffer, StringBuilder intro, List<DetalhesAplicativoSteam.DestaqueSteam> destaques) {
    String texto = limparEspacos(buffer.toString());
    if (titulo == null) {
      intro.append(texto).append(' ');
    } else if (!texto.isBlank()) {
      destaques.add(new DetalhesAplicativoSteam.DestaqueSteam(titulo, texto));
    }
  }

  private static String textoDoNode(Node node) {
    if (node instanceof TextNode texto) return texto.text();
    if (node instanceof Element el) return el.text();
    return "";
  }

  private static String limparEspacos(String texto) {
    return texto.replaceAll("\\s+", " ").trim();
  }

  private static String comoRequisitos(Object valor) {
    String html = comoTexto(valor);
    if (html == null || html.isBlank()) return null;
    Document doc = Jsoup.parseBodyFragment(html);
    List<String> linhas = new ArrayList<>();
    for (Element li : doc.select("li")) {
      String texto = limparEspacos(li.text());
      if (!texto.isBlank()) linhas.add(texto);
    }
    if (!linhas.isEmpty()) {
      return String.join("\n", linhas);
    }
    String textoSimples = limparEspacos(doc.text());
    return textoSimples.isBlank() ? null : textoSimples;
  }

  private static String comoDataLancamento(Object valor) {
    Map<?, ?> mapa = comoMapa(valor);
    return mapa == null ? null : comoTexto(mapa.get("date"));
  }

  private static List<String> comoListaDeCampo(Object valor, String campo) {
    List<String> resultado = new ArrayList<>();
    for (Object item : comoLista(valor)) {
      Map<?, ?> mapa = comoMapa(item);
      String texto = mapa == null ? null : comoTexto(mapa.get(campo));
      if (texto != null && !texto.isBlank()) resultado.add(texto);
    }
    return resultado;
  }

  private static List<String> comoListaDeTexto(Object valor) {
    List<String> resultado = new ArrayList<>();
    for (Object item : comoLista(valor)) {
      if (item instanceof String texto && !texto.isBlank()) resultado.add(texto);
    }
    return resultado;
  }

  private static List<Integer> comoListaDeInteiros(Object valor) {
    List<Integer> resultado = new ArrayList<>();
    for (Object item : comoLista(valor)) {
      Integer numero = comoInteiro(item);
      if (numero != null) resultado.add(numero);
    }
    return resultado;
  }

  private static Map<?, ?> comoMapa(Object valor) {
    return valor instanceof Map<?, ?> mapa ? mapa : null;
  }

  private static List<?> comoLista(Object valor) {
    return valor instanceof List<?> lista ? lista : List.of();
  }

  private static String comoTexto(Object valor) {
    return valor instanceof String texto ? texto : null;
  }

  private static Integer comoInteiro(Object valor) {
    return valor instanceof Number numero ? numero.intValue() : null;
  }

  // A Steam retorna "percent" como string (ex: "83.3"), nao como numero.
  private static Double comoDouble(Object valor) {
    if (valor instanceof Number numero) return numero.doubleValue();
    if (valor instanceof String texto) {
      try {
        return Double.parseDouble(texto);
      } catch (NumberFormatException ignorado) {
        return null;
      }
    }
    return null;
  }

  public record ReviewsSteam(String descricaoNota, Integer positivas, Integer negativas) {}
  public record ConquistaEsquemaSteam(String nome, String tituloExibicao, String descricao, String iconeUrl, String iconeCinzaUrl) {}
  private record PerfilAutorSteam(String nome, String avatarUrl) {}

  private record PaginaAvaliacoes(List<Map<?, ?>> avaliacoes, String proximoCursor) {}
  private record SobreParseado(String texto, List<DetalhesAplicativoSteam.DestaqueSteam> destaques) {}
}
