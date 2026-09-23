package com.ofertagames.backend.sitemap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.ofertagames.backend.jogos.RepositorioJogos;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class ServicoSitemapTest {

  @Mock private RepositorioJogos jogos;
  private ServicoSitemap servico;

  private static final String FRONTEND = "https://ofertagames.vercel.app";
  private static final String BACKEND = "https://api.exemplo-backend.test";

  @BeforeEach
  void configurar() {
    MockitoAnnotations.openMocks(this);
    // Ambas com barra final de proposito: cobre a normalizacao das duas de uma vez.
    servico = new ServicoSitemap(jogos, FRONTEND + "/", BACKEND + "/");
  }

  private static RepositorioJogos.JogoParaSitemap jogo(String slug, Instant atualizadoEm) {
    return new RepositorioJogos.JogoParaSitemap(slug, atualizadoEm);
  }

  @Test
  void calculaTotalDePaginasArredondandoPraCima() {
    when(jogos.contarJogosParaSitemap()).thenReturn(110_299L);
    assertEquals(12, servico.totalPaginasDeJogos());
  }

  @Test
  void indiceApontaProsSubSitemapsNoBACKEND() {
    when(jogos.contarJogosParaSitemap()).thenReturn(25_000L);
    String indice = servico.gerarIndice();
    assertTrue(indice.contains("<loc>" + BACKEND + "/sitemap-estatico.xml</loc>"));
    assertTrue(indice.contains("<loc>" + BACKEND + "/sitemap-jogos-1.xml</loc>"));
    assertTrue(indice.contains("<loc>" + BACKEND + "/sitemap-jogos-3.xml</loc>"));
  }

  /**
   * Regressao do bug corrigido em 01/09/2026: o indice apontava pro dominio do frontend, que
   * responde HTML pra esses caminhos, entao o crawler nunca chegava nos sub-sitemaps. O teste
   * antigo afirmava justamente o comportamento errado.
   */
  @Test
  void indiceNaoPodeApontarProFrontend() {
    when(jogos.contarJogosParaSitemap()).thenReturn(25_000L);
    String indice = servico.gerarIndice();
    assertFalse(indice.contains(FRONTEND), "os <loc> do indice sao sub-sitemaps servidos pelo backend");
  }

  @Test
  void removeBarraFinalDasDuasUrlsPraNaoDuplicar() {
    // configurar() injeta as duas URLs com barra final; garante que nao vira "//sitemap".
    when(jogos.contarJogosParaSitemap()).thenReturn(1L);
    assertFalse(servico.gerarIndice().contains("//sitemap"));
    assertFalse(servico.gerarEstatico().contains("app//"));
  }

  @Test
  void sitemapEstaticoIncluiAsPaginasPrincipaisNoFRONTEND() {
    String xml = servico.gerarEstatico();
    assertTrue(xml.contains("<loc>" + FRONTEND + "</loc>"));
    assertTrue(xml.contains("<loc>" + FRONTEND + "/catalogo</loc>"));
    assertTrue(xml.contains("<loc>" + FRONTEND + "/mais-vendidos</loc>"));
    assertTrue(xml.contains("<loc>" + FRONTEND + "/gratuitos</loc>"));
    // As paginas em si sao do site, nunca do backend.
    assertFalse(xml.contains(BACKEND));
  }

  @Test
  void paginaDeJogosMontaUrlPorSlugNoFRONTEND() {
    when(jogos.listarJogosParaSitemap(0, ServicoSitemap.TAMANHO_PAGINA)).thenReturn(List.of(
        jogo("baldurs-gate-3", null), jogo("cult-of-the-lamb", null)));
    String xml = servico.gerarPaginaDeJogos(1);
    assertTrue(xml.contains("<loc>" + FRONTEND + "/jogo/baldurs-gate-3</loc>"));
    assertTrue(xml.contains("<loc>" + FRONTEND + "/jogo/cult-of-the-lamb</loc>"));
    assertFalse(xml.contains(BACKEND));
  }

  @Test
  void paginaDeJogosUsaDeslocamentoCorretoParaPaginasSeguintes() {
    when(jogos.listarJogosParaSitemap(2, ServicoSitemap.TAMANHO_PAGINA)).thenReturn(List.of(jogo("jogo-x", null)));
    // pagina 3 (1-indexed) deve pedir a pagina 2 (0-indexed) ao repositorio.
    String xml = servico.gerarPaginaDeJogos(3);
    assertTrue(xml.contains("jogo-x"));
  }

  @Test
  void lastmodSaiComoDataSemHora() {
    when(jogos.listarJogosParaSitemap(0, ServicoSitemap.TAMANHO_PAGINA))
        .thenReturn(List.of(jogo("hollow-knight", Instant.parse("2026-09-14T23:45:07Z"))));
    assertTrue(servico.gerarPaginaDeJogos(1).contains(
        "<loc>" + FRONTEND + "/jogo/hollow-knight</loc><lastmod>2026-09-14</lastmod>"));
  }

  /**
   * {@code lastmod} so vale enquanto o crawler pode confiar nele: jogo sem data conhecida sai sem
   * a tag, nunca com a data de hoje (que faria toda URL alegar "mudei agora" e o Google passar a
   * ignorar o campo no sitemap inteiro).
   */
  @Test
  void jogoSemDataConhecidaSaiSemLastmod() {
    when(jogos.listarJogosParaSitemap(0, ServicoSitemap.TAMANHO_PAGINA)).thenReturn(List.of(jogo("jogo-novo", null)));
    String xml = servico.gerarPaginaDeJogos(1);
    assertTrue(xml.contains("<loc>" + FRONTEND + "/jogo/jogo-novo</loc></url>"));
    assertFalse(xml.contains("<lastmod>"));
  }

  /** O Google ignora changefreq; deixar nas paginas de jogo so competia com o lastmod. */
  @Test
  void paginaDeJogosNaoUsaChangefreq() {
    when(jogos.listarJogosParaSitemap(0, ServicoSitemap.TAMANHO_PAGINA))
        .thenReturn(List.of(jogo("elden-ring", Instant.parse("2026-09-01T10:00:00Z"))));
    assertFalse(servico.gerarPaginaDeJogos(1).contains("changefreq"));
  }

  @Test
  void preservaAOrdemQueORepositorioDevolveu() {
    // A ordem e por rank (o repositorio ordena); o XML nao pode reordenar, senao a pagina 1 deixa
    // de ser "os mais populares".
    when(jogos.listarJogosParaSitemap(0, ServicoSitemap.TAMANHO_PAGINA)).thenReturn(List.of(
        jogo("baldurs-gate-3", null), jogo("cyberpunk-2077", null), jogo("elden-ring", null)));
    String xml = servico.gerarPaginaDeJogos(1);
    assertTrue(xml.indexOf("baldurs-gate-3") < xml.indexOf("cyberpunk-2077"));
    assertTrue(xml.indexOf("cyberpunk-2077") < xml.indexOf("elden-ring"));
  }
}
