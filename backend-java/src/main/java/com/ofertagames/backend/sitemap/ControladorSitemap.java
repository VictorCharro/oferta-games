package com.ofertagames.backend.sitemap;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

// Sitemap fica no dominio do backend e e referenciado via "Sitemap:" no robots.txt do frontend
// (dominio cruzado, aceito pelo Google quando declarado assim). Nao precisa estar no mesmo host
// da Vercel.
@RestController
public class ControladorSitemap {
  private final ServicoSitemap sitemap;

  ControladorSitemap(ServicoSitemap sitemap) {
    this.sitemap = sitemap;
  }

  @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
  ResponseEntity<String> indice() {
    return comCache(sitemap.gerarIndice());
  }

  @GetMapping(value = "/sitemap-estatico.xml", produces = MediaType.APPLICATION_XML_VALUE)
  ResponseEntity<String> estatico() {
    return comCache(sitemap.gerarEstatico());
  }

  @GetMapping(value = "/sitemap-jogos-{pagina}.xml", produces = MediaType.APPLICATION_XML_VALUE)
  ResponseEntity<String> jogos(@PathVariable int pagina) {
    if (pagina < 1 || pagina > sitemap.totalPaginasDeJogos()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return comCache(sitemap.gerarPaginaDeJogos(pagina));
  }

  private ResponseEntity<String> comCache(String corpo) {
    return ResponseEntity.ok()
        .header("Cache-Control", "public, max-age=3600")
        .body(corpo);
  }
}
