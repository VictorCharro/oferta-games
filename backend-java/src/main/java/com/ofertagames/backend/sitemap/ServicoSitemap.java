package com.ofertagames.backend.sitemap;

import com.ofertagames.backend.jogos.RepositorioJogos;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// Sitemap dividido em paginas (indice + sub-sitemaps) porque o catalogo passa de 100k jogos,
// acima do limite de 50.000 URLs por arquivo do protocolo de sitemaps.
@Service
public class ServicoSitemap {
  public static final int TAMANHO_PAGINA = 10_000;

  private final RepositorioJogos jogos;
  private final String frontendUrl;

  ServicoSitemap(RepositorioJogos jogos, @Value("${app.frontend-url}") String frontendUrl) {
    this.jogos = jogos;
    this.frontendUrl = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
  }

  public String gerarIndice() {
    int totalPaginas = totalPaginasDeJogos();
    StringBuilder xml = new StringBuilder();
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    xml.append("<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
    xml.append("  <sitemap><loc>").append(frontendUrl).append("/sitemap-estatico.xml</loc></sitemap>\n");
    for (int pagina = 0; pagina < totalPaginas; pagina++) {
      xml.append("  <sitemap><loc>").append(frontendUrl).append("/sitemap-jogos-").append(pagina + 1).append(".xml</loc></sitemap>\n");
    }
    xml.append("</sitemapindex>\n");
    return xml.toString();
  }

  public String gerarEstatico() {
    StringBuilder xml = new StringBuilder();
    abrirUrlset(xml);
    for (String caminho : List.of("", "/catalogo", "/mais-vendidos", "/gratuitos")) {
      adicionarUrl(xml, caminho, "daily");
    }
    fecharUrlset(xml);
    return xml.toString();
  }

  public String gerarPaginaDeJogos(int pagina) {
    List<String> slugs = jogos.listarSlugsParaSitemap(pagina - 1, TAMANHO_PAGINA);
    StringBuilder xml = new StringBuilder();
    abrirUrlset(xml);
    for (String slug : slugs) {
      adicionarUrl(xml, "/jogo/" + slug, "weekly");
    }
    fecharUrlset(xml);
    return xml.toString();
  }

  public int totalPaginasDeJogos() {
    long total = jogos.contarSlugsParaSitemap();
    return (int) Math.ceil(total / (double) TAMANHO_PAGINA);
  }

  private void abrirUrlset(StringBuilder xml) {
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
  }

  private void fecharUrlset(StringBuilder xml) {
    xml.append("</urlset>\n");
  }

  private void adicionarUrl(StringBuilder xml, String caminho, String changefreq) {
    xml.append("  <url><loc>").append(frontendUrl).append(caminho)
        .append("</loc><changefreq>").append(changefreq).append("</changefreq></url>\n");
  }
}
