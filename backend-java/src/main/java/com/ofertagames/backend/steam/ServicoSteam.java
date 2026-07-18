package com.ofertagames.backend.steam;

import com.ofertagames.backend.comum.ClassificadorDlc;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class ServicoSteam {
  private static final Pattern APP_ID = Pattern.compile("store\\.steampowered\\.com/app/(\\d+)");

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

  public Optional<String> resolverAppIdSteam(String urlOferta) {
    try {
      HttpRequest requisicao = HttpRequest.newBuilder(URI.create(urlOferta)).GET().build();
      HttpResponse<Void> resposta = clienteHttp.send(requisicao, HttpResponse.BodyHandlers.discarding());
      return extrairAppIdSteam(resposta.uri().toString());
    } catch (Exception ignored) {
      return extrairAppIdSteam(urlOferta);
    }
  }

  public Optional<DetalhesAplicativoSteam> buscarDetalhesAplicativo(String appId) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> resposta = restClient.get()
          .uri("https://store.steampowered.com/api/appdetails?appids={appId}&l=portuguese", appId)
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

      return Optional.of(new DetalhesAplicativoSteam(
          ehDlc, imagemCabecalho, descricaoCurta, generos, desenvolvedores, publicadoras, dataLancamento, screenshots,
          trailerUrl, trailerThumbnail, sobre.texto(), sobre.destaques(), categorias, requisitosMinimos, requisitosRecomendados));
    } catch (RuntimeException ignored) {
      return Optional.empty();
    }
  }

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

  // A Steam manda a descricao longa em HTML com blocos "<h2 class=bb_tag>Titulo</h2>texto...":
  // o texto antes do primeiro titulo vira a descricao completa, cada bloco depois vira um destaque.
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
  private record SobreParseado(String texto, List<DetalhesAplicativoSteam.DestaqueSteam> destaques) {}
}
