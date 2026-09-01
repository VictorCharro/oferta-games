package com.ofertagames.backend.autenticacao;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Resolve o usuario autenticado a partir do token Bearer do Supabase Auth.
 *
 * <p>Nao valida o JWT localmente: <b>delega ao Supabase</b> chamando {@code /auth/v1/user}. A
 * vantagem e nunca precisar do segredo de assinatura no backend nem lidar com rotacao de chave; o
 * custo e depender da disponibilidade do Supabase para qualquer rota autenticada.
 *
 * <p>O resultado positivo fica em cache por {@value #VALIDADE_CACHE_SEGUNDOS}s (ver
 * {@link #buscarUsuarioPeloCabecalho}), entao nao ha mais uma chamada de rede por requisicao.
 */
@Service
public class ServicoAutenticacao {
  /**
   * Janela do cache de validacao.
   *
   * <p>O risco de cachear e um token continuar aceito por ate esse tempo depois de invalidado. E
   * pequeno porque o token de acesso do Supabase ja e um JWT de vida propria (~1h): sair da conta
   * nao o revoga imediatamente de qualquer forma, entao a janela real de exposicao muda pouco.
   * Aumentar muito este valor inverte essa conta — nao subir sem pensar nisso.
   */
  private static final int VALIDADE_CACHE_SEGUNDOS = 60;

  private final RestClient restClient;
  private final String urlSupabase;
  private final String chaveAnonima;

  /**
   * Chaveado pelo <b>hash</b> do token, nao pelo token.
   *
   * <p>O valor em cache (o id do usuario) nao serve para se autenticar; o token, sim. Guardar so o
   * hash evita manter credencial reutilizavel viva em memoria por um minuto, onde um dump de heap a
   * pegaria pronta para uso.
   */
  private final Cache<String, String> usuariosPorToken = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofSeconds(VALIDADE_CACHE_SEGUNDOS))
      .maximumSize(10_000)
      .build();

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
   * <p><b>So o sucesso e cacheado</b> (por {@value #VALIDADE_CACHE_SEGUNDOS}s). Falha nao entra no
   * cache de proposito: como toda falha e indistinguivel — inclusive "Supabase fora do ar" —,
   * cachear negativo faria uma instabilidade de um segundo virar um minuto de usuarios deslogados.
   * O custo dessa escolha e que token invalido sempre bate no Supabase; quem contem enxurrada disso
   * e o {@code FiltroLimiteRequisicoes}, nao este cache.
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
    String chaveCache = hash(token);
    String cacheado = usuariosPorToken.getIfPresent(chaveCache);
    if (cacheado != null) {
      return Optional.of(cacheado);
    }

    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> resposta = restClient.get()
          .uri(urlSupabase + "/auth/v1/user")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
          .header("apikey", chaveAnonima)
          .retrieve()
          .body(Map.class);

      Object id = resposta == null ? null : resposta.get("id");
      if (id instanceof String valor && !valor.isBlank()) {
        usuariosPorToken.put(chaveCache, valor);
        return Optional.of(valor);
      }
      return Optional.empty();
    } catch (RuntimeException ignored) {
      return Optional.empty();
    }
  }

  private static String hash(String token) {
    try {
      byte[] resumo = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(resumo);
    } catch (NoSuchAlgorithmException erro) {
      // SHA-256 e obrigatorio em toda JVM; se faltar, o ambiente esta quebrado de um jeito que nao
      // da pra contornar aqui.
      throw new IllegalStateException("SHA-256 indisponivel", erro);
    }
  }
}
