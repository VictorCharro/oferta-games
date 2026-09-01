package com.ofertagames.backend.sitemap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.ofertagames.backend.jogos.RepositorioJogos;
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

  @Test
  void calculaTotalDePaginasArredondandoPraCima() {
    when(jogos.contarSlugsParaSitemap()).thenReturn(110_299L);
    assertEquals(12, servico.totalPaginasDeJogos());
  }

  @Test
  void indiceApontaProsSubSitemapsNoBACKEND() {
    when(jogos.contarSlugsParaSitemap()).thenReturn(25_000L);
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
    when(jogos.contarSlugsParaSitemap()).thenReturn(25_000L);
    String indice = servico.gerarIndice();
    assertFalse(indice.contains(FRONTEND), "os <loc> do indice sao sub-sitemaps servidos pelo backend");
  }

  @Test
  void removeBarraFinalDasDuasUrlsPraNaoDuplicar() {
    // configurar() injeta as duas URLs com barra final; garante que nao vira "//sitemap".
    when(jogos.contarSlugsParaSitemap()).thenReturn(1L);
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
    when(jogos.listarSlugsParaSitemap(0, ServicoSitemap.TAMANHO_PAGINA)).thenReturn(List.of("baldurs-gate-3", "cult-of-the-lamb"));
    String xml = servico.gerarPaginaDeJogos(1);
    assertTrue(xml.contains("<loc>" + FRONTEND + "/jogo/baldurs-gate-3</loc>"));
    assertTrue(xml.contains("<loc>" + FRONTEND + "/jogo/cult-of-the-lamb</loc>"));
    assertFalse(xml.contains(BACKEND));
  }

  @Test
  void paginaDeJogosUsaDeslocamentoCorretoParaPaginasSeguintes() {
    when(jogos.listarSlugsParaSitemap(2, ServicoSitemap.TAMANHO_PAGINA)).thenReturn(List.of("jogo-x"));
    // pagina 3 (1-indexed) deve pedir a pagina 2 (0-indexed) ao repositorio.
    String xml = servico.gerarPaginaDeJogos(3);
    assertTrue(xml.contains("jogo-x"));
  }
}
