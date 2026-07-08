package com.ofertagames.backend.steam;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class ServicoSteam {
  private static final Pattern APP_ID = Pattern.compile("store\\.steampowered\\.com/app/(\\d+)");
  private static final Pattern[] PADROES_TITULO_DLC = {
      Pattern.compile("\\bDLC\\b", Pattern.CASE_INSENSITIVE),
      Pattern.compile("\\bSeason Pass\\b", Pattern.CASE_INSENSITIVE),
      Pattern.compile("\\bSoundtrack\\b", Pattern.CASE_INSENSITIVE),
      Pattern.compile("\\bOST\\b", Pattern.CASE_INSENSITIVE),
      Pattern.compile("\\bArt Book\\b", Pattern.CASE_INSENSITIVE),
      Pattern.compile("\\bSkin Set\\b", Pattern.CASE_INSENSITIVE),
      Pattern.compile("\\bSkin Pack\\b", Pattern.CASE_INSENSITIVE),
      Pattern.compile("\\bBooster Pack\\b", Pattern.CASE_INSENSITIVE),
      Pattern.compile("\\bExpansion\\b", Pattern.CASE_INSENSITIVE),
      Pattern.compile("\\bAdd-on\\b", Pattern.CASE_INSENSITIVE)
  };

  private final RestClient restClient;
  private final HttpClient clienteHttp;

  ServicoSteam(RestClient.Builder restClientBuilder) {
    this.restClient = restClientBuilder.build();
    this.clienteHttp = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build();
  }

  public boolean tituloPareceDlc(String titulo) {
    for (Pattern padrao : PADROES_TITULO_DLC) {
      if (padrao.matcher(titulo).find()) {
        return true;
      }
    }
    return false;
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
      String imagemCabecalho = dados.get("header_image") instanceof String valor ? valor : null;
      return Optional.of(new DetalhesAplicativoSteam(ehDlc, imagemCabecalho));
    } catch (RuntimeException ignored) {
      return Optional.empty();
    }
  }

  private Optional<String> extrairAppIdSteam(String url) {
    var matcher = APP_ID.matcher(url);
    return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
  }
}
