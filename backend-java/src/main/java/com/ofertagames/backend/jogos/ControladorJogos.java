package com.ofertagames.backend.jogos;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/games")
public class ControladorJogos {
  private final RepositorioJogos jogos;
  private final ServicoCatalogo catalogo;

  ControladorJogos(RepositorioJogos jogos, ServicoCatalogo catalogo) {
    this.jogos = jogos;
    this.catalogo = catalogo;
  }

  @GetMapping
  List<ResumoJogo> listar(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "rank") String sort,
      @RequestParam(defaultValue = "all") String type,
      @RequestParam(required = false) Double minPrice,
      @RequestParam(required = false) Double maxPrice,
      @RequestParam(required = false) String q
  ) {
    int paginaSegura = Math.max(0, page);
    int tamanhoSeguro = Math.min(100, Math.max(1, size));
    return jogos.listar(paginaSegura, tamanhoSeguro, sort, type, minPrice, maxPrice, q);
  }

  @GetMapping("/search")
  ResponseEntity<?> buscar(@RequestParam(defaultValue = "") String q) {
    try {
      return ResponseEntity.ok(catalogo.buscarComFallbackItad(q));
    } catch (ServicoCatalogo.BuscaCurtaException erro) {
      return ResponseEntity.badRequest().body(Map.of("error", "Busca muito curta"));
    }
  }

  @GetMapping("/{slug}")
  ResponseEntity<?> detalhar(@PathVariable String slug) {
    return jogos.buscarPorSlug(slug)
        .<ResponseEntity<?>>map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "Jogo nao encontrado")));
  }

  @PostMapping("/{slug}/refresh")
  ResponseEntity<?> atualizarPrecos(@PathVariable String slug) {
    try {
      return ResponseEntity.ok(catalogo.atualizarPrecos(slug));
    } catch (ServicoCatalogo.JogoNaoEncontradoException erro) {
      return ResponseEntity.status(404).body(Map.of("error", "Jogo nao encontrado"));
    } catch (ServicoCatalogo.JogoSemItadException erro) {
      return ResponseEntity.badRequest().body(Map.of("error", "Jogo sem id da ITAD"));
    }
  }
}
