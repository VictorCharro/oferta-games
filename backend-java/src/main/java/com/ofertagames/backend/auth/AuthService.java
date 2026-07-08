package com.ofertagames.backend.auth;

import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class AuthService {
  private final RestClient restClient;
  private final String supabaseUrl;
  private final String anonKey;

  AuthService(
      RestClient.Builder restClientBuilder,
      @Value("${app.supabase.url}") String supabaseUrl,
      @Value("${app.supabase.anon-key}") String anonKey
  ) {
    this.restClient = restClientBuilder.build();
    this.supabaseUrl = supabaseUrl;
    this.anonKey = anonKey;
  }

  public Optional<String> userIdFromAuthorization(String authorization) {
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      return Optional.empty();
    }
    if (supabaseUrl == null || supabaseUrl.isBlank() || anonKey == null || anonKey.isBlank()) {
      return Optional.empty();
    }

    String token = authorization.substring("Bearer ".length());
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> response = restClient.get()
          .uri(supabaseUrl + "/auth/v1/user")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
          .header("apikey", anonKey)
          .retrieve()
          .body(Map.class);

      Object id = response == null ? null : response.get("id");
      return id instanceof String value && !value.isBlank() ? Optional.of(value) : Optional.empty();
    } catch (RuntimeException ignored) {
      return Optional.empty();
    }
  }
}
