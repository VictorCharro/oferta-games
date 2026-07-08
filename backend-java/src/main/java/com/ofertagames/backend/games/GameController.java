package com.ofertagames.backend.games;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/games")
public class GameController {
  private final GameRepository games;

  GameController(GameRepository games) {
    this.games = games;
  }

  @GetMapping
  List<GameSummary> index(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "rank") String sort,
      @RequestParam(defaultValue = "all") String type,
      @RequestParam(required = false) Double minPrice,
      @RequestParam(required = false) Double maxPrice,
      @RequestParam(required = false) String q
  ) {
    int safePage = Math.max(0, page);
    int safeSize = Math.min(100, Math.max(1, size));
    return games.findGames(safePage, safeSize, sort, type, minPrice, maxPrice, q);
  }

  @GetMapping("/search")
  List<GameSummary> search(@RequestParam(defaultValue = "") String q) {
    return games.findGames(0, 20, "rank", "all", null, null, q);
  }

  @GetMapping("/{slug}")
  ResponseEntity<?> show(@PathVariable String slug) {
    return games.findBySlug(slug)
        .<ResponseEntity<?>>map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.status(404).body(new ErrorResponse("Game not found")));
  }

  record ErrorResponse(String error) {}
}
