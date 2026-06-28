package com.ofertagames.backend.offer;

import com.ofertagames.backend.game.Game;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OfferRepository extends JpaRepository<Offer, Long> {

    List<Offer> findByGameOrderByPriceAsc(Game game);

    Optional<Offer> findByGameAndSourceAndStoreName(Game game, String source, String storeName);
}
