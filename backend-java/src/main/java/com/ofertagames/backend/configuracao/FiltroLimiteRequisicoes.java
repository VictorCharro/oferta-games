package com.ofertagames.backend.configuracao;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limite de requisicoes por IP, em janela fixa de 1 minuto.
 *
 * <p><b>O problema do SSR.</b> As leituras publicas nao vem so de navegadores: o SSR na Vercel
 * chama esta API pra renderizar cada pagina, e todas essas chamadas saem de um punhado de IPs da
 * Vercel. Com limite por IP, todos os visitantes renderizados dividiam o mesmo balde — com ~2 page
 * views por segundo o balde de leitura estourava e o SSR recebia 429, renderizando erro pra todo
 * mundo (issue #21).
 *
 * <p><b>A saida: token compartilhado.</b> O SSR manda {@value #CABECALHO_TOKEN_SSR} com um segredo
 * ({@code SSR_API_TOKEN}, configurado na Vercel e na VM). Requisicao com token valido usa um balde
 * proprio, bem maior ({@link #LIMITE_SSR}), que so existe como rede de seguranca caso o segredo
 * vaze. Todo o resto e navegador, e ai o limite de leitura pode ser de navegador.
 *
 * <p><b>O limite de navegador so aperta depois que o SSR PROVA que manda o token.</b> Enquanto nao
 * chega nenhuma requisicao com token valido (Vercel ainda sem a variavel, ou deploy antigo), o SSR
 * continua caindo no balde comum, e apertar ali derrubaria o site. Por isso a leitura sem token
 * usa {@link #LIMITE_LEITURA} (alto, o de antes) ate ver um token valido nos ultimos
 * {@link #CONFIRMACAO_SSR}, e so entao passa pra {@link #LIMITE_LEITURA_NAVEGADOR}. A ordem em que
 * VM e Vercel sao configuradas deixa de importar, e se a Vercel perder a variavel o limite volta a
 * afrouxar sozinho em 15 minutos.
 *
 * <p><b>Nao protege login</b>: a autenticacao acontece direto entre o navegador e o Supabase, sem
 * passar por aqui, entao forca bruta de senha e limitada pelo proprio Supabase — nao por este
 * filtro. Ver doc.md, secao Seguranca.
 *
 * <p>Estado em memoria, por instancia. Como o backend roda em uma unica VM, isso basta; com mais de
 * uma instancia o limite efetivo passaria a ser o dobro e seria preciso um contador compartilhado.
 */
@Component
public class FiltroLimiteRequisicoes extends OncePerRequestFilter {
  private static final Logger logger = LoggerFactory.getLogger(FiltroLimiteRequisicoes.class);

  static final String CABECALHO_TOKEN_SSR = "X-SSR-Token";

  /** Leitura sem token enquanto o SSR nao confirmou que manda o token: alto pra acomodar o SSR. */
  static final int LIMITE_LEITURA = 600;

  /** Leitura sem token depois da confirmacao: so navegador cai aqui. Uma pagina faz de 2 a 6 GETs. */
  static final int LIMITE_LEITURA_NAVEGADOR = 120;

  /** Balde do SSR autenticado. Folgado de proposito: e rede de seguranca pra token vazado, nao cota. */
  static final int LIMITE_SSR = 6_000;

  /** Escrita so parte de navegador real, entao pode ser perto do uso humano. */
  static final int LIMITE_ESCRITA = 30;

  static final Duration CONFIRMACAO_SSR = Duration.ofMinutes(15);

  private static final Duration JANELA = Duration.ofMinutes(1);

  private final Cache<String, AtomicInteger> contadores = Caffeine.newBuilder()
      .expireAfterWrite(JANELA)
      .maximumSize(50_000)
      .build();

  private final Set<String> origensPermitidas;
  private final byte[] tokenSsr;
  private final Clock relogio;
  /** Instante (millis) do ultimo token SSR valido; 0 = nunca visto desde o boot. */
  private final AtomicLong ultimoTokenSsrValido = new AtomicLong(0);

  @Autowired
  FiltroLimiteRequisicoes(
      @Value("${app.cors.allowed-origins:*}") String origens,
      @Value("${app.ssr.token:}") String tokenSsr) {
    this(origens, tokenSsr, Clock.systemUTC());
  }

  FiltroLimiteRequisicoes(String origens, String tokenSsr, Clock relogio) {
    this.origensPermitidas = Arrays.stream(origens.split(","))
        .map(String::trim)
        .filter(origem -> !origem.isBlank())
        .collect(Collectors.toUnmodifiableSet());
    this.tokenSsr = tokenSsr == null || tokenSsr.isBlank() ? null : tokenSsr.trim().getBytes(StandardCharsets.UTF_8);
    this.relogio = relogio;
  }

  /** Construtor dos testes antigos: sem token SSR configurado. */
  FiltroLimiteRequisicoes(String origens) {
    this(origens, "", Clock.systemUTC());
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest requisicao) {
    // Health check do Docker/Caddy bate de forma constante e nao representa custo nem risco.
    return requisicao.getRequestURI().startsWith("/actuator");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
      throws ServletException, IOException {
    boolean escrita = !("GET".equals(requisicao.getMethod()) || "HEAD".equals(requisicao.getMethod())
        || "OPTIONS".equals(requisicao.getMethod()));
    boolean ssr = !escrita && tokenSsrValido(requisicao);

    int limite;
    String balde;
    if (ssr) {
      limite = LIMITE_SSR;
      balde = "s:";
    } else if (escrita) {
      limite = LIMITE_ESCRITA;
      balde = "w:";
    } else {
      limite = ssrConfirmado() ? LIMITE_LEITURA_NAVEGADOR : LIMITE_LEITURA;
      balde = "r:";
    }
    String chave = balde + ipCliente(requisicao);

    int usadas = contadores.get(chave, k -> new AtomicInteger()).incrementAndGet();
    if (usadas > limite) {
      if (usadas == limite + 1) {
        // Uma linha por IP por janela, nao uma por requisicao recusada.
        logger.warn("Limite de requisicoes atingido: balde={} limite={}/min ip={}", balde, limite, ipCliente(requisicao));
      }
      resposta.setStatus(429);
      resposta.setHeader("Retry-After", String.valueOf(JANELA.toSeconds()));
      resposta.setContentType("application/json");
      liberarCorsNaRecusa(requisicao, resposta);
      resposta.getWriter().write("{\"error\":\"Muitas requisicoes. Tente novamente em instantes.\"}");
      return;
    }
    cadeia.doFilter(requisicao, resposta);
  }

  /** Comparacao em tempo constante: o tempo de resposta nao pode revelar quantos bytes acertou. */
  private boolean tokenSsrValido(HttpServletRequest requisicao) {
    if (tokenSsr == null) return false;
    String recebido = requisicao.getHeader(CABECALHO_TOKEN_SSR);
    if (recebido == null) return false;
    boolean valido = MessageDigest.isEqual(tokenSsr, recebido.getBytes(StandardCharsets.UTF_8));
    if (valido) ultimoTokenSsrValido.set(relogio.millis());
    return valido;
  }

  private boolean ssrConfirmado() {
    long visto = ultimoTokenSsrValido.get();
    return visto != 0 && relogio.millis() - visto < CONFIRMACAO_SSR.toMillis();
  }

  /**
   * Repete o cabecalho de CORS ao recusar.
   *
   * <p>O CORS do Spring e aplicado no handler, depois dos filtros — entao uma resposta cortada aqui
   * sairia sem {@code Access-Control-Allow-Origin} e o navegador reportaria "erro de CORS" no lugar
   * do 429. O usuario (e quem for depurar) veria um problema que nao existe, em vez da limitacao
   * que de fato aconteceu.
   *
   * <p>So repete origem que ja esta na lista permitida: devolver o {@code Origin} recebido sem
   * conferir transformaria esta resposta numa excecao a politica de CORS do resto da API.
   */
  private void liberarCorsNaRecusa(HttpServletRequest requisicao, HttpServletResponse resposta) {
    String origem = requisicao.getHeader("Origin");
    if (origem == null) {
      return;
    }
    if (origensPermitidas.contains("*") || origensPermitidas.contains(origem)) {
      resposta.setHeader("Access-Control-Allow-Origin", origem);
      resposta.setHeader("Vary", "Origin");
    }
  }

  /**
   * IP do cliente conforme o Caddy o enxergou.
   *
   * <p>Usa a <b>ultima</b> entrada de {@code X-Forwarded-For}, nao a primeira: o Caddy <i>anexa</i>
   * o peer real ao que veio na requisicao, entao um cliente que mande o cabecalho forjado consegue
   * plantar valores no inicio da lista — mas nunca no fim. Ler a primeira entrada deixaria qualquer
   * um trocar de "identidade" a cada request e furar o limite.
   */
  private static String ipCliente(HttpServletRequest requisicao) {
    String encaminhado = requisicao.getHeader("X-Forwarded-For");
    if (encaminhado == null || encaminhado.isBlank()) {
      return requisicao.getRemoteAddr();
    }
    String[] partes = encaminhado.split(",");
    return partes[partes.length - 1].trim();
  }
}
