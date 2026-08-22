package com.ofertagames.backend.jogos;

import com.ofertagames.backend.autenticacao.ServicoAutenticacao;
import com.ofertagames.backend.steam.RespostaAvaliacoesSteam;
import com.ofertagames.backend.steam.ServicoSteam;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/games")
public class ControladorJogos {
  private final RepositorioJogos jogos;
  private final ServicoCatalogo catalogo;
  private final ServicoConquistasJogo conquistasJogo;
  private final ServicoAutenticacao autenticacao;
  private final ServicoSteam steam;

  ControladorJogos(
      RepositorioJogos jogos,
      ServicoCatalogo catalogo,
      ServicoConquistasJogo conquistasJogo,
      ServicoAutenticacao autenticacao,
      ServicoSteam steam) {
    this.jogos = jogos;
    this.catalogo = catalogo;
    this.conquistasJogo = conquistasJogo;
    this.autenticacao = autenticacao;
    this.steam = steam;
  }

  @GetMapping
  List<ResumoJogo> listar(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "rank") String sort,
      @RequestParam(defaultValue = "all") String type,
      @RequestParam(defaultValue = "all") String platform,
      @RequestParam(required = false) Double minPrice,
      @RequestParam(required = false) Double maxPrice,
      @RequestParam(required = false) Double minDiscount,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String stores
  ) {
    int paginaSegura = Math.max(0, page);
    int tamanhoSeguro = Math.min(100, Math.max(1, size));
    Double descontoSeguro = minDiscount == null ? null : Math.min(100, Math.max(0, minDiscount));
    List<String> lojas = stores == null || stores.isBlank()
        ? List.of()
        : Arrays.stream(stores.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    return jogos.listar(paginaSegura, tamanhoSeguro, sort, type, platform, minPrice, maxPrice, descontoSeguro, q, lojas);
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
  ResponseEntity<DetalheJogo> detalhar(@PathVariable String slug) {
    return jogos.buscarPorSlug(slug)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @GetMapping("/{slug}/historico-precos")
  ResponseEntity<List<RepositorioJogos.PontoHistoricoPreco>> historicoPrecos(
      @PathVariable String slug,
      @RequestParam(defaultValue = "90") int dias
  ) {
    int diasSeguro = Math.min(90, Math.max(1, dias));
    return jogos.buscarIdPorSlug(slug)
        .map(id -> ResponseEntity.ok(jogos.listarHistoricoDePrecos(id, diasSeguro)))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @GetMapping("/{slug}/detalhes")
  ResponseEntity<DetalhesJogo> detalhes(@PathVariable String slug) {
    return jogos.buscarIdPorSlug(slug)
        .flatMap(jogos::buscarDetalhesJogo)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @GetMapping("/{slug}/avaliacoes/steam")
  ResponseEntity<RespostaAvaliacoesSteam> avaliacoesSteam(
      @PathVariable String slug,
      @RequestParam(defaultValue = "") String cursor,
      @RequestParam(defaultValue = "recent") String ordenacao,
      @RequestParam(defaultValue = "brazilian") String idioma
  ) {
    return jogos.buscarIdESteamAppIdPorSlug(slug)
        .filter(jogo -> jogo.steamAppId() != null)
        .map(jogo -> steam.buscarAvaliacoes(
            String.valueOf(jogo.steamAppId()),
            cursor,
            ordenacao,
            idioma))
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.ok(
            new RespostaAvaliacoesSteam(null, List.of(), null, false, idioma, ordenacao)));
  }

  @GetMapping("/{slug}/conquistas")
  ResponseEntity<RespostaConquistas> conquistas(
      @PathVariable String slug,
      @RequestHeader(value = "Authorization", required = false) String autorizacao
  ) {
    String visitanteId = autenticacao.buscarUsuarioPeloCabecalho(autorizacao).orElse(null);
    return conquistasJogo.buscar(slug, visitanteId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.ok(new RespostaConquistas(0, 0, 0, null, List.of())));
  }

  @PostMapping("/{slug}/refresh")
  ResponseEntity<?> atualizarPrecos(@PathVariable String slug) {
    try {
      return ResponseEntity.ok(catalogo.atualizarPrecos(slug));
    } catch (ServicoCatalogo.JogoNaoEncontradoException erro) {
      return ResponseEntity.status(404).body(Map.of("error", "Jogo nao encontrado"));
    } catch (ServicoCatalogo.JogoSemItadException erro) {
      return ResponseEntity.badRequest().body(Map.of("error", "Jogo sem fonte de precos para atualizar"));
    } catch (ServicoCatalogo.RefreshRecenteException erro) {
      return ResponseEntity.status(429)
          .header("Retry-After", String.valueOf(erro.segundosRestantes()))
          .body(Map.of("error", "Precos atualizados recentemente", "segundosRestantes", erro.segundosRestantes()));
    }
  }
}
