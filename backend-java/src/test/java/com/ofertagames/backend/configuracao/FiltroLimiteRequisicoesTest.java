package com.ofertagames.backend.configuracao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class FiltroLimiteRequisicoesTest {
  private static final String ORIGEM_PERMITIDA = "https://ofertagames.vercel.app";

  private FiltroLimiteRequisicoes filtro;

  @BeforeEach
  void configurar() {
    filtro = new FiltroLimiteRequisicoes(ORIGEM_PERMITIDA + ",http://localhost:4200");
  }

  private int disparar(String metodo, String ipEncaminhado, int vezes) throws Exception {
    int ultimoStatus = 0;
    for (int i = 0; i < vezes; i++) {
      MockHttpServletRequest requisicao = new MockHttpServletRequest(metodo, "/api/favorites");
      if (ipEncaminhado != null) requisicao.addHeader("X-Forwarded-For", ipEncaminhado);
      MockHttpServletResponse resposta = new MockHttpServletResponse();
      filtro.doFilter(requisicao, resposta, new MockFilterChain());
      ultimoStatus = resposta.getStatus();
    }
    return ultimoStatus;
  }

  @Test
  void escritaEBloqueadaAoPassarDoLimite() throws Exception {
    assertEquals(200, disparar("POST", "1.1.1.1", FiltroLimiteRequisicoes.LIMITE_ESCRITA));
    assertEquals(429, disparar("POST", "1.1.1.1", 1));
  }

  @Test
  void leituraTemTetoMuitoMaiorQueEscrita() throws Exception {
    // O SSR da Vercel concentra muitos usuarios em poucos IPs; um teto de escrita em GET
    // derrubaria o site sob trafego normal.
    assertTrue(FiltroLimiteRequisicoes.LIMITE_LEITURA > FiltroLimiteRequisicoes.LIMITE_ESCRITA * 10);
    assertEquals(200, disparar("GET", "2.2.2.2", FiltroLimiteRequisicoes.LIMITE_ESCRITA + 50));
  }

  @Test
  void limiteEPorIp() throws Exception {
    assertEquals(200, disparar("POST", "3.3.3.3", FiltroLimiteRequisicoes.LIMITE_ESCRITA));
    assertEquals(429, disparar("POST", "3.3.3.3", 1));
    // Outro IP nao herda o bloqueio do vizinho.
    assertEquals(200, disparar("POST", "4.4.4.4", 1));
  }

  @Test
  void leituraEEscritaTemContadoresSeparados() throws Exception {
    assertEquals(200, disparar("POST", "5.5.5.5", FiltroLimiteRequisicoes.LIMITE_ESCRITA));
    assertEquals(429, disparar("POST", "5.5.5.5", 1));
    // Estourar escrita nao pode derrubar a leitura do mesmo IP.
    assertEquals(200, disparar("GET", "5.5.5.5", 1));
  }

  /**
   * O cliente consegue plantar entradas no INICIO do X-Forwarded-For, mas nunca no fim — o Caddy
   * anexa o peer real. Ler a primeira entrada deixaria qualquer um trocar de identidade a cada
   * request e furar o limite.
   */
  @Test
  void ipForjadoNoInicioDoCabecalhoNaoFuraOLimite() throws Exception {
    for (int i = 0; i < FiltroLimiteRequisicoes.LIMITE_ESCRITA; i++) {
      disparar("POST", "9.9.9." + i + ", 6.6.6.6", 1);
    }
    // Mesmo variando o valor forjado a cada request, o IP real (ultimo) ja estourou.
    assertEquals(429, disparar("POST", "1.2.3.4, 6.6.6.6", 1));
  }

  @Test
  void semCabecalhoUsaOEnderecoDaConexao() throws Exception {
    assertEquals(200, disparar("POST", null, FiltroLimiteRequisicoes.LIMITE_ESCRITA));
    assertEquals(429, disparar("POST", null, 1));
  }

  @Test
  void actuatorNaoELimitado() {
    MockHttpServletRequest requisicao = new MockHttpServletRequest("GET", "/actuator/health");
    assertTrue(filtro.shouldNotFilter(requisicao));
  }

  private MockHttpServletResponse recusarComOrigem(String origem) throws Exception {
    MockHttpServletResponse resposta = new MockHttpServletResponse();
    for (int i = 0; i <= FiltroLimiteRequisicoes.LIMITE_ESCRITA; i++) {
      MockHttpServletRequest requisicao = new MockHttpServletRequest("POST", "/api/favorites");
      requisicao.addHeader("X-Forwarded-For", "7.7.7.7");
      if (origem != null) requisicao.addHeader("Origin", origem);
      resposta = new MockHttpServletResponse();
      filtro.doFilter(requisicao, resposta, new MockFilterChain());
    }
    return resposta;
  }

  /**
   * Sem isto o navegador reporta "erro de CORS" no lugar do 429, escondendo o motivo real — o CORS
   * do Spring roda no handler, depois dos filtros, e nunca chega a ser aplicado numa resposta
   * cortada aqui.
   */
  @Test
  void recusaDevolveCorsParaOrigemPermitida() throws Exception {
    MockHttpServletResponse resposta = recusarComOrigem(ORIGEM_PERMITIDA);
    assertEquals(429, resposta.getStatus());
    assertEquals(ORIGEM_PERMITIDA, resposta.getHeader("Access-Control-Allow-Origin"));
    assertEquals("60", resposta.getHeader("Retry-After"));
  }

  @Test
  void recusaNaoLiberaCorsParaOrigemDesconhecida() throws Exception {
    MockHttpServletResponse resposta = recusarComOrigem("https://site-invasor.example");
    assertEquals(429, resposta.getStatus());
    assertNull(resposta.getHeader("Access-Control-Allow-Origin"));
  }
}
