package com.ofertagames.backend.source.itad;

import com.ofertagames.backend.game.Game;
import com.ofertagames.backend.game.GameRepository;
import com.ofertagames.backend.offer.Offer;
import com.ofertagames.backend.offer.OfferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class ItadSyncService {

    private static final Logger log = LoggerFactory.getLogger(ItadSyncService.class);
    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGES = 10;

    private final ItadClient itadClient;
    private final GameRepository gameRepository;
    private final OfferRepository offerRepository;

    public ItadSyncService(ItadClient itadClient, GameRepository gameRepository, OfferRepository offerRepository) {
        this.itadClient = itadClient;
        this.gameRepository = gameRepository;
        this.offerRepository = offerRepository;
    }

    @Transactional
    public void sync() {
        log.info("Starting ITAD sync");

        for (int page = 0; page < MAX_PAGES; page++) {
            List<ItadGame> games = itadClient.searchGames(PAGE_SIZE, page * PAGE_SIZE);
            if (games == null || games.isEmpty()) break;

            for (ItadGame itadGame : games) {
                upsertGame(itadGame);
            }

            List<String> ids = games.stream().map(ItadGame::id).toList();
            List<ItadPriceResult> prices = itadClient.getPrices(ids);
            if (prices != null) {
                for (ItadPriceResult result : prices) {
                    upsertOffers(result);
                }
            }

            log.info("Synced page {}", page + 1);
        }

        log.info("ITAD sync complete");
    }

    private void upsertGame(ItadGame itadGame) {
        UUID itadId = UUID.fromString(itadGame.id());
        Game game = gameRepository.findByItadId(itadId).orElseGet(Game::new);
        game.setItadId(itadId);
        game.setTitle(itadGame.title());
        game.setSlug(itadGame.slug());
        if (itadGame.assets() != null) {
            game.setCoverUrl(itadGame.assets().banner400());
        }
        gameRepository.save(game);
    }

    private void upsertOffers(ItadPriceResult result) {
        UUID itadId = UUID.fromString(result.id());
        gameRepository.findByItadId(itadId).ifPresent(game -> {
            for (ItadPriceResult.Deal deal : result.deals()) {
                Offer offer = offerRepository
                        .findByGameAndSourceAndStoreName(game, "itad", deal.shop().name())
                        .orElseGet(Offer::new);

                offer.setGame(game);
                offer.setSource("itad");
                offer.setStoreName(deal.shop().name());
                offer.setPrice(deal.price().amount());
                offer.setRegularPrice(deal.regular() != null ? deal.regular().amount() : null);
                offer.setCurrency("BRL");
                offer.setUrl(deal.url());
                offer.setUpdatedAt(OffsetDateTime.now());

                offerRepository.save(offer);
            }
        });
    }
}
