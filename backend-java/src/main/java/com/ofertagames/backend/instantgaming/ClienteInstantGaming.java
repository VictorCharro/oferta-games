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

/**
 * Le preco e nome direto do HTML das paginas de produto da Instant Gaming — unica excecao de
 * scraping do projeto, porque eles nao tem API publica nem estao na ITAD (contato oficial feito e
 * negado).
 *
 * <p><b>Limite auto-imposto:</b> so le paginas de produto individuais, que o robots.txt deles
 * permite. Nunca usa a busca do site, que o robots.txt bloqueia explicitamente — e por isso que a
 * descoberta varre ids sequenciais em vez de pesquisar por titulo.
 *
 * <p>Os dados saem das meta tags schema.org ({@code itemprop="name"/"price"/"priceCurrency"}),
 * escolha deliberada por serem mais estaveis que classes CSS visuais, que mudam a cada
 * redesign.
 *
 * <p>Toda falha (rede, HTTP, HTML fora do formato) vira {@link Optional#empty()}: nao existe
 * distincao entre "produto nao existe" e "nao consegui ler". Para a varredura por id sequencial os
 * dois casos sao equivalentes, mas quem chamar precisa saber que um empty nao prova ausencia.
 */
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

  /**
   * Extrai titulo, preco, moeda e URL canonica do HTML.
   *
   * <p>Rejeita explicitamente produto <b>fora de estoque</b>: nesse caso a pagina deles mantem
   * {@code itemprop="price"} como {@code "0.00"} em vez de omitir o campo, e sem essa checagem o
   * produto aparecia como o menor preco do catalogo inteiro — de graca e sem poder comprar. Alem
   * da flag {@code availability}, qualquer preco {@code <= 0} tambem e rejeitado, como cinto de
   * seguranca caso eles mudem o formato.
   *
   * @return vazio quando o produto nao existe, esta fora de estoque, ou o HTML nao tem as meta
   *     tags esperadas
   */
  private Optional<ProdutoInstantGaming> parsear(String html) {
    Document documento = Jsoup.parse(html);
    Element container = documento.selectFirst("#product-app");
    if (container == null) {
      return Optional.empty();
    }

    Element nomeEl = container.selectFirst("meta[itemprop=name]");
    Element precoEl = container.selectFirst("meta[itemprop=price]");
    Element moedaEl = container.selectFirst("meta[itemprop=priceCurrency]");
    Element disponibilidadeEl = container.selectFirst("meta[itemprop=availability]");
    Element canonicalEl = documento.selectFirst("link[rel=canonical]");
    if (nomeEl == null || precoEl == null || canonicalEl == null) {
      return Optional.empty();
    }

    // Fora de estoque: a pagina mantem o preco como "0.00" em vez de omitir o campo, o que sem
    // essa checagem parecia o menor preco do catalogo (produto de graca que nem da pra comprar).
    if (disponibilidadeEl != null && disponibilidadeEl.attr("content").toLowerCase(java.util.Locale.ROOT).contains("outofstock")) {
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
    if (preco.signum() <= 0) {
      return Optional.empty();
    }

    String moeda = moedaEl != null && !moedaEl.attr("content").isBlank() ? moedaEl.attr("content").trim() : "BRL";
    return Optional.of(new ProdutoInstantGaming(titulo, preco, moeda, url));
  }

  record ProdutoInstantGaming(String titulo, BigDecimal preco, String moeda, String url) {}
}
