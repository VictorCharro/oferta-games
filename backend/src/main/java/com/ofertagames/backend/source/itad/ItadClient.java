package com.ofertagames.backend.source.itad;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class ItadClient {

    private static final String BASE_URL = "https://api.isthereanydeal.com";

    private final RestClient restClient;
    private final String apiKey;

    public ItadClient(@Value("${itad.api.key}") String apiKey) {
        this.apiKey = apiKey;
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("ITAD-API-Key", apiKey)
                .build();
    }

    public List<ItadGame> searchGames(int limit, int offset) {
        return restClient.get()
                .uri("/games/search/v1?limit={limit}&offset={offset}", limit, offset)
                .retrieve()
                .body(new ParameterizedTypeReference<List<ItadGame>>() {});
    }

    public List<ItadPriceResult> getPrices(List<String> itadIds) {
        return restClient.post()
                .uri("/games/prices/v3?country=BR&shops=61,35,16")
                .body(itadIds)
                .retrieve()
                .body(new ParameterizedTypeReference<List<ItadPriceResult>>() {});
    }
}
