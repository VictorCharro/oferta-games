package com.ofertagames.backend.autenticacao;

import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class ServicoAutenticacao {
  private final RestClient restClient;
  private final String urlSupabase;
  private final String chaveAnonima;

  ServicoAutenticacao(
      RestClient.Builder restClientBuilder,
      @Value("${app.supabase.url}") String urlSupabase,
      @Value("${app.supabase.anon-key}") String chaveAnonima
  ) {
    this.restClient = restClientBuilder.build();
    this.urlSupabase = urlSupabase;
    this.chaveAnonima = chaveAnonima;
  }

  public Optional<String> buscarUsuarioPeloCabecalho(String cabecalhoAutorizacao) {
    if (cabecalhoAutorizacao == null || !cabecalhoAutorizacao.startsWith("Bearer ")) {
      return Optional.empty();
    }
    if (urlSupabase == null || urlSupabase.isBlank() || chaveAnonima == null || chaveAnonima.isBlank()) {
      return Optional.empty();
    }

    String token = cabecalhoAutorizacao.substring("Bearer ".length());
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> resposta = restClient.get()
          .uri(urlSupabase + "/auth/v1/user")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
          .header("apikey", chaveAnonima)
          .retrieve()
          .body(Map.class);

      Object id = resposta == null ? null : resposta.get("id");
      return id instanceof String valor && !valor.isBlank() ? Optional.of(valor) : Optional.empty();
    } catch (RuntimeException ignored) {
      return Optional.empty();
    }
  }
}
