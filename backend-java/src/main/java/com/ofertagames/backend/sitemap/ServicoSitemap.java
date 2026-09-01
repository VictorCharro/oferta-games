package com.ofertagames.backend.sitemap;

import com.ofertagames.backend.jogos.RepositorioJogos;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Gera o sitemap.xml. Dividido em paginas (indice + sub-sitemaps) porque o catalogo passa de 100k
 * jogos, acima do limite de 50.000 URLs por arquivo do protocolo de sitemaps.
 *
 * <p><b>Sao dois dominios diferentes, e trocar um pelo outro quebra o sitemap inteiro:</b>
 *
 * <ul>
 *   <li>as URLs <i>dentro</i> de cada sub-sitemap sao paginas do site, entao usam
 *       {@code frontendUrl};
 *   <li>os {@code <loc>} do <i>indice</i> apontam pros proprios sub-sitemaps, que quem serve e
 *       este backend — entao usam {@code backendUrl}.
 * </ul>
 *
 * <p>O indice usava {@code frontendUrl} nos dois casos ate 01/09/2026. O crawler buscava o indice
 * aqui, era mandado pro dominio do frontend e recebia <b>HTML em vez de XML</b> (o frontend
 * responde 200 com a pagina do app pra qualquer caminho desconhecido), entao nenhuma pagina do
 * catalogo chegava a ser descoberta pelo sitemap.
 */
@Service
public class ServicoSitemap {
  public static final int TAMANHO_PAGINA = 10_000;

  private final RepositorioJogos jogos;
  private final String frontendUrl;
  private final String backendUrl;

  /**
   * Reusa {@code app.public-backend-url} (o mesmo do retorno OpenID da Steam) em vez de criar uma
   * variavel nova, pra existir um unico lugar dizendo qual e o dominio publico deste backend. Ela
   * ja e obrigatoria em producao; o fallback pro localhost so serve pra rodar local sem configurar
   * nada.
   */
  ServicoSitemap(RepositorioJogos jogos,
      @Value("${app.frontend-url}") String frontendUrl,
      @Value("${app.public-backend-url:}") String backendUrl) {
    this.jogos = jogos;
    this.frontendUrl = semBarraFinal(frontendUrl);
    this.backendUrl = semBarraFinal(backendUrl.isBlank() ? "http://localhost:8080" : backendUrl);
  }

  private static String semBarraFinal(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  public String gerarIndice() {
    int totalPaginas = totalPaginasDeJogos();
    StringBuilder xml = new StringBuilder();
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    xml.append("<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
    // backendUrl, nao frontendUrl: estes <loc> sao os proprios sub-sitemaps, servidos aqui.
    xml.append("  <sitemap><loc>").append(backendUrl).append("/sitemap-estatico.xml</loc></sitemap>\n");
    for (int pagina = 0; pagina < totalPaginas; pagina++) {
      xml.append("  <sitemap><loc>").append(backendUrl).append("/sitemap-jogos-").append(pagina + 1).append(".xml</loc></sitemap>\n");
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
