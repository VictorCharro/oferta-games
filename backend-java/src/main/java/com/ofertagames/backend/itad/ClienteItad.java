package com.ofertagames.backend.itad;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class ClienteItad {
  private static final String URL_BASE = "https://api.isthereanydeal.com";
  private static final String LOJAS = "50,6,37,24,42,19,61,16,4,52,48,62";

  private final RestClient restClient;
  private final String chaveApi;

  ClienteItad(RestClient.Builder restClientBuilder, @Value("${app.itad.api-key}") String chaveApi) {
    this.restClient = restClientBuilder.baseUrl(URL_BASE).build();
    this.chaveApi = chaveApi;
  }

  public RespostaOfertasItad buscarOfertas(int limite, int deslocamento) {
    return restClient.get()
        .uri(uri -> uri.path("/deals/v2")
            .queryParam("country", "BR")
            .queryParam("shops", LOJAS)
            .queryParam("sort", "rank")
            .queryParam("limit", limite)
            .queryParam("offset", deslocamento)
            .build())
        .header("ITAD-API-Key", chaveApi)
        .retrieve()
        .body(RespostaOfertasItad.class);
  }

  public List<ResultadoBuscaItad> buscarJogos(String titulo) {
    return restClient.get()
        .uri(uri -> uri.path("/games/search/v1")
            .queryParam("title", titulo)
            .queryParam("limit", 10)
            .build())
        .header("ITAD-API-Key", chaveApi)
        .retrieve()
        .body(new ParameterizedTypeReference<List<ResultadoBuscaItad>>() {});
  }

  public List<ResultadoPrecoItad> buscarPrecos(String itadId) {
    return restClient.post()
        .uri("/games/prices/v3?country=BR")
        .header("ITAD-API-Key", chaveApi)
        .body(List.of(itadId))
        .retrieve()
        .body(new ParameterizedTypeReference<List<ResultadoPrecoItad>>() {});
  }
}
