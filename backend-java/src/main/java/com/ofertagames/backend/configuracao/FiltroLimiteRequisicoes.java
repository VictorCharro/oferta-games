package com.ofertagames.backend.configuracao;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limite de requisicoes por IP, em janela fixa de 1 minuto.
 *
 * <p><b>Dois limites diferentes, e a razao importa.</b> As leituras publicas nao vem so de
 * navegadores: o SSR na Vercel chama esta API para renderizar cada pagina, e todas essas chamadas
 * saem de um punhado de IPs da Vercel. Um limite apertado em GET derrubaria o site inteiro sob
 * trafego normal — nao o atacante. Por isso o teto de leitura e alto o bastante para nao alcancar
 * o SSR (serve so para conter enxurrada de um host so), enquanto a escrita, que so parte de
 * navegador real, e limitada de verdade.
 *
 * <p>Se o trafego crescer a ponto de o SSR encostar em {@link #LIMITE_LEITURA}, a saida nao e
 * afrouxar o numero: e isentar o SSR com um cabecalho secreto compartilhado, ai sim podendo apertar
 * a leitura para valores de navegador.
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
  /** Alto de proposito: precisa acomodar o SSR da Vercel, que concentra muitos usuarios em poucos IPs. */
  static final int LIMITE_LEITURA = 600;

  /** Escrita so parte de navegador real, entao pode ser perto do uso humano. */
  static final int LIMITE_ESCRITA = 30;

  private static final Duration JANELA = Duration.ofMinutes(1);

  private final Cache<String, AtomicInteger> contadores = Caffeine.newBuilder()
      .expireAfterWrite(JANELA)
      .maximumSize(50_000)
      .build();

  private final Set<String> origensPermitidas;

  FiltroLimiteRequisicoes(@Value("${app.cors.allowed-origins:*}") String origens) {
    this.origensPermitidas = Arrays.stream(origens.split(","))
        .map(String::trim)
        .filter(origem -> !origem.isBlank())
        .collect(Collectors.toUnmodifiableSet());
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
    int limite = escrita ? LIMITE_ESCRITA : LIMITE_LEITURA;
    String chave = (escrita ? "w:" : "r:") + ipCliente(requisicao);

    int usadas = contadores.get(chave, k -> new AtomicInteger()).incrementAndGet();
    if (usadas > limite) {
      resposta.setStatus(429);
      resposta.setHeader("Retry-After", String.valueOf(JANELA.toSeconds()));
      resposta.setContentType("application/json");
      liberarCorsNaRecusa(requisicao, resposta);
      resposta.getWriter().write("{\"error\":\"Muitas requisicoes. Tente novamente em instantes.\"}");
      return;
    }
    cadeia.doFilter(requisicao, resposta);
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
