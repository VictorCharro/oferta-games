package com.ofertagames.backend.sitemap;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

  @BeforeEach
  void configurar() {
    MockitoAnnotations.openMocks(this);
    servico = new ServicoSitemap(jogos, "https://ofertagames.vercel.app/");
  }

  @Test
  void calculaTotalDePaginasArredondandoPraCima() {
    when(jogos.contarSlugsParaSitemap()).thenReturn(110_299L);
    assertEquals(12, servico.totalPaginasDeJogos());
  }

  @Test
  void indiceListaSitemapEstaticoETodasAsPaginasDeJogos() {
    when(jogos.contarSlugsParaSitemap()).thenReturn(25_000L);
    String indice = servico.gerarIndice();
    assertTrue(indice.contains("<loc>https://ofertagames.vercel.app/sitemap-estatico.xml</loc>"));
    assertTrue(indice.contains("<loc>https://ofertagames.vercel.app/sitemap-jogos-1.xml</loc>"));
    assertTrue(indice.contains("<loc>https://ofertagames.vercel.app/sitemap-jogos-3.xml</loc>"));
  }

  @Test
  void removeBarraFinalDaUrlDoFrontendPraNaoDuplicar() {
    // configurar() ja injeta a URL do frontend com barra final; garante que nao vira "//sitemap".
    when(jogos.contarSlugsParaSitemap()).thenReturn(1L);
    String indice = servico.gerarIndice();
    assertTrue(indice.contains("vercel.app/sitemap-estatico.xml"));
    assertTrue(!indice.contains("vercel.app//sitemap"));
  }

  @Test
  void sitemapEstaticoIncluiAsPaginasPrincipais() {
    String xml = servico.gerarEstatico();
    assertTrue(xml.contains("<loc>https://ofertagames.vercel.app</loc>"));
    assertTrue(xml.contains("<loc>https://ofertagames.vercel.app/catalogo</loc>"));
    assertTrue(xml.contains("<loc>https://ofertagames.vercel.app/mais-vendidos</loc>"));
    assertTrue(xml.contains("<loc>https://ofertagames.vercel.app/gratuitos</loc>"));
  }

  @Test
  void paginaDeJogosMontaUrlPorSlug() {
    when(jogos.listarSlugsParaSitemap(0, ServicoSitemap.TAMANHO_PAGINA)).thenReturn(List.of("baldurs-gate-3", "cult-of-the-lamb"));
    String xml = servico.gerarPaginaDeJogos(1);
    assertTrue(xml.contains("<loc>https://ofertagames.vercel.app/jogo/baldurs-gate-3</loc>"));
    assertTrue(xml.contains("<loc>https://ofertagames.vercel.app/jogo/cult-of-the-lamb</loc>"));
  }

  @Test
  void paginaDeJogosUsaDeslocamentoCorretoParaPaginasSeguintes() {
    when(jogos.listarSlugsParaSitemap(2, ServicoSitemap.TAMANHO_PAGINA)).thenReturn(List.of("jogo-x"));
    // pagina 3 (1-indexed) deve pedir a pagina 2 (0-indexed) ao repositorio.
    String xml = servico.gerarPaginaDeJogos(3);
    assertTrue(xml.contains("jogo-x"));
  }
}
