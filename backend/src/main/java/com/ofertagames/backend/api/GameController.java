package com.ofertagames.backend.api;

import com.ofertagames.backend.game.GameRepository;
import com.ofertagames.backend.game.GameSummaryProjection;
import com.ofertagames.backend.offer.Offer;
import com.ofertagames.backend.offer.OfferRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/games")
public class GameController {

    private final GameRepository gameRepository;
    private final OfferRepository offerRepository;

    public GameController(GameRepository gameRepository, OfferRepository offerRepository) {
        this.gameRepository = gameRepository;
        this.offerRepository = offerRepository;
    }

    @GetMapping
    public List<GameSummaryProjection> listGames(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return gameRepository.findAllWithMinPrice(PageRequest.of(page, Math.min(size, 100)));
    }

    @GetMapping("/{slug}")
    public ResponseEntity<Map<String, Object>> getGame(@PathVariable String slug) {
        return gameRepository.findBySlug(slug)
                .map(game -> {
                    List<Offer> offers = offerRepository.findByGameOrderByPriceAsc(game);
                    return ResponseEntity.ok(Map.of(
                            "slug", game.getSlug(),
                            "title", game.getTitle(),
                            "coverUrl", game.getCoverUrl() != null ? game.getCoverUrl() : "",
                            "offers", offers.stream().map(o -> Map.of(
                                    "storeName", o.getStoreName(),
                                    "price", o.getPrice(),
                                    "regularPrice", o.getRegularPrice() != null ? o.getRegularPrice() : o.getPrice(),
                                    "currency", o.getCurrency(),
                                    "url", o.getUrl()
                            )).toList()
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
