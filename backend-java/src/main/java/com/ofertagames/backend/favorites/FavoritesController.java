package com.ofertagames.backend.favorites;

import com.ofertagames.backend.auth.AuthService;
import com.ofertagames.backend.games.GameRepository;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/favorites")
public class FavoritesController {
  private final AuthService auth;
  private final FavoritesRepository favorites;
  private final GameRepository games;

  FavoritesController(AuthService auth, FavoritesRepository favorites, GameRepository games) {
    this.auth = auth;
    this.favorites = favorites;
    this.games = games;
  }

  @GetMapping
  ResponseEntity<?> index(@RequestHeader(value = "Authorization", required = false) String authorization) {
    return auth.userIdFromAuthorization(authorization)
        .<ResponseEntity<?>>map(userId -> ResponseEntity.ok(favorites.findByUser(userId)))
        .orElseGet(() -> ResponseEntity.status(401).body(Map.of("error", "Nao autenticado")));
  }

  @PostMapping
  ResponseEntity<?> create(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody(required = false) FavoriteRequest request
  ) {
    var userId = auth.userIdFromAuthorization(authorization);
    if (userId.isEmpty()) {
      return ResponseEntity.status(401).body(Map.of("error", "Nao autenticado"));
    }
    if (request == null || request.slug() == null || request.slug().isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("error", "slug e obrigatorio"));
    }

    var gameId = games.findIdBySlug(request.slug());
    if (gameId.isEmpty()) {
      return ResponseEntity.status(404).body(Map.of("error", "Jogo nao encontrado"));
    }

    favorites.add(userId.get(), gameId.get());
    return ResponseEntity.status(201).body(Map.of("ok", true));
  }

  @DeleteMapping("/{slug}")
  ResponseEntity<?> delete(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String slug
  ) {
    var userId = auth.userIdFromAuthorization(authorization);
    if (userId.isEmpty()) {
      return ResponseEntity.status(401).body(Map.of("error", "Nao autenticado"));
    }

    var gameId = games.findIdBySlug(slug);
    if (gameId.isEmpty()) {
      return ResponseEntity.status(404).body(Map.of("error", "Jogo nao encontrado"));
    }

    favorites.delete(userId.get(), gameId.get());
    return ResponseEntity.ok(Map.of("ok", true));
  }
}
