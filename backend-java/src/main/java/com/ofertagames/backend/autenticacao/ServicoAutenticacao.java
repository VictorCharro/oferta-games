package com.ofertagames.backend.autenticacao;

import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Resolve o usuario autenticado a partir do token Bearer do Supabase Auth.
 *
 * <p>Nao valida o JWT localmente: <b>delega ao Supabase</b> chamando {@code /auth/v1/user} a cada
 * requisicao autenticada. A vantagem e nunca precisar do segredo de assinatura no backend nem
 * lidar com rotacao de chave; o custo e uma chamada de rede por request e a dependencia da
 * disponibilidade do Supabase pra qualquer rota autenticada. Nao ha cache dessa validacao.
 */
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

  /**
   * Extrai o id do usuario (UUID de {@code auth.users}) do cabecalho {@code Authorization}.
   *
   * <p><b>Todo caminho de falha devolve vazio</b>, sem distincao: cabecalho ausente ou malformado,
   * token invalido ou expirado, Supabase fora do ar, ou o proprio backend sem
   * {@code SUPABASE_URL}/{@code SUPABASE_ANON_KEY} configurados. Quem chama nao consegue separar
   * "nao autenticado" de "falha na validacao" e responde 401 nos dois casos.
   *
   * <p>Consequencia operacional a ter em mente: se as variaveis do Supabase faltarem, ou o servico
   * ficar indisponivel, <b>toda</b> rota autenticada passa a responder 401 como se os usuarios
   * tivessem sido deslogados, em vez de acusar erro de infraestrutura.
   *
   * @param cabecalhoAutorizacao valor cru do header, esperado no formato {@code "Bearer <token>"};
   *     aceita {@code null}
   */
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
