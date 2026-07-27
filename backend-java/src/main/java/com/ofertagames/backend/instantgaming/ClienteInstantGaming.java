package com.ofertagames.backend.instantgaming;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Optional;
import java.util.function.Function;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

// So le paginas de produto individuais (permitido pelo robots.txt deles); nunca usa a busca do
// site, que o robots.txt bloqueia explicitamente. Preco/nome vem de meta tags schema.org
// (itemprop="name"/"price"/"priceCurrency"), mais estavel que depender de classes CSS visuais.
@Component
class ClienteInstantGaming {
  private static final String USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

  private final RestClient restClient;

  ClienteInstantGaming(RestClient.Builder restClientBuilder) {
    this.restClient = restClientBuilder
        .baseUrl("https://www.instant-gaming.com")
        .defaultHeader("User-Agent", USER_AGENT)
        .defaultHeader("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
        .build();
  }

  Optional<ProdutoInstantGaming> buscarProduto(int idProduto) {
    return buscar(uriBuilder -> uriBuilder.path("/en/{id}-x/").build(idProduto));
  }

  // Usado pra reatualizar preco de um produto ja casado, direto pela URL canonica salva.
  Optional<ProdutoInstantGaming> buscarProdutoPorUrl(String urlAbsoluta) {
    return buscar(uriBuilder -> URI.create(urlAbsoluta));
  }

  private Optional<ProdutoInstantGaming> buscar(Function<UriBuilder, URI> uri) {
    try {
      String html = restClient.get()
          .uri(uri)
          .retrieve()
          .body(String.class);
      return html == null ? Optional.empty() : parsear(html);
    } catch (RestClientException erro) {
      return Optional.empty();
    }
  }

  private Optional<ProdutoInstantGaming> parsear(String html) {
    Document documento = Jsoup.parse(html);
    Element container = documento.selectFirst("#product-app");
    if (container == null) {
      return Optional.empty();
    }

    Element nomeEl = container.selectFirst("meta[itemprop=name]");
    Element precoEl = container.selectFirst("meta[itemprop=price]");
    Element moedaEl = container.selectFirst("meta[itemprop=priceCurrency]");
    Element canonicalEl = documento.selectFirst("link[rel=canonical]");
    if (nomeEl == null || precoEl == null || canonicalEl == null) {
      return Optional.empty();
    }

    String titulo = nomeEl.attr("content").trim();
    String url = canonicalEl.attr("href").trim();
    if (titulo.isBlank() || url.isBlank()) {
      return Optional.empty();
    }

    BigDecimal preco;
    try {
      preco = new BigDecimal(precoEl.attr("content").trim());
    } catch (NumberFormatException erro) {
      return Optional.empty();
    }

    String moeda = moedaEl != null && !moedaEl.attr("content").isBlank() ? moedaEl.attr("content").trim() : "BRL";
    return Optional.of(new ProdutoInstantGaming(titulo, preco, moeda, url));
  }

  record ProdutoInstantGaming(String titulo, BigDecimal preco, String moeda, String url) {}
}
