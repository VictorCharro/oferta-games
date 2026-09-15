package com.ofertagames.backend.itad;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

  /**
   * Id da ITAD de cada app da Steam (loja 61). App que a ITAD nao conhece volta com valor nulo e
   * fica fora do mapa.
   */
  public Map<Integer, String> buscarIdsPorAppSteam(List<Integer> appIds) {
    if (appIds == null || appIds.isEmpty()) return Map.of();
    Map<String, String> resposta = restClient.post()
        .uri("/lookup/id/shop/61/v1")
        .header("ITAD-API-Key", chaveApi)
        .body(appIds.stream().map(id -> "app/" + id).toList())
        .retrieve()
        .body(new ParameterizedTypeReference<Map<String, String>>() {});
    Map<Integer, String> ids = new LinkedHashMap<>();
    if (resposta == null) return ids;
    resposta.forEach((chave, idItad) -> {
      if (idItad != null && chave.startsWith("app/")) ids.put(Integer.parseInt(chave.substring(4)), idItad);
    });
    return ids;
  }

  /** Titulo, slug, tipo e capa de um jogo pelo id da ITAD. */
  public InfoJogoItad buscarInfoJogo(String idItad) {
    return restClient.get()
        .uri(uri -> uri.path("/games/info/v2").queryParam("id", idItad).build())
        .header("ITAD-API-Key", chaveApi)
        .retrieve()
        .body(InfoJogoItad.class);
  }

  public List<ResultadoPrecoItad> buscarPrecos(String itadId) {
    return buscarPrecos(List.of(itadId));
  }

  public List<ResultadoPrecoItad> buscarPrecos(List<String> idsItad) {
    if (idsItad == null || idsItad.isEmpty()) {
      return List.of();
    }
    return restClient.post()
        // vouchers=true: inclui ofertas com cupom aplicado (o price/cut ja vem calculado com o
        // desconto do cupom), pra mostrar o menor preco real em vez de so o preco de tabela da loja.
        .uri("/games/prices/v3?country=BR&vouchers=true")
        .header("ITAD-API-Key", chaveApi)
        .body(idsItad)
        .retrieve()
        .body(new ParameterizedTypeReference<List<ResultadoPrecoItad>>() {});
  }
}
