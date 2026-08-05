package com.ofertagames.backend.conexoes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

// Fluxo "Xbox App" do OpenXBL (xbl.io): diferente da API key pessoal, esse app_key e usado
// pra logar QUALQUER usuario com a conta Microsoft dele (nao so a nossa) - o usuario e
// redirecionado pra https://xbl.io/app/auth/{app_key}, volta com um "code" na URL, e a gente
// troca esse code por xuid/gamertag/token nesse endpoint /app/claim.
@Service
class ClienteXbl {
  private static final String URL_BASE = "https://xbl.io";

  private final RestClient restClient;
  private final String appKey;

  ClienteXbl(RestClient.Builder restClientBuilder, @Value("${app.xbox.app-key}") String appKey) {
    this.restClient = restClientBuilder.baseUrl(URL_BASE).build();
    this.appKey = appKey;
  }

  boolean configurada() {
    return appKey != null && !appKey.isBlank();
  }

  String appKey() {
    return appKey;
  }

  RespostaClaimXbl trocarCodigo(String code) {
    return restClient.post()
        .uri("/app/claim")
        .body(Map.of("code", code, "app_key", appKey))
        .retrieve()
        .body(RespostaClaimXbl.class);
  }

  // "app_key" aqui e o token por usuario devolvido pelo /app/claim (mesmo nome de campo da
  // chave do app, mas com outro significado) - usado pra chamar a API em nome desse usuario.
  @JsonIgnoreProperties(ignoreUnknown = true)
  record RespostaClaimXbl(
      String xuid,
      String gamertag,
      String avatar,
      Integer gamerscore,
      @JsonProperty("app_key") String tokenUsuario,
      String message
  ) {}
}
