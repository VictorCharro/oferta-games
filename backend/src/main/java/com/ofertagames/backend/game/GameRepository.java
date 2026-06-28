package com.ofertagames.backend.game;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GameRepository extends JpaRepository<Game, Long> {

    Optional<Game> findByItadId(UUID itadId);

    Optional<Game> findBySlug(String slug);

    @Query("""
        SELECT new com.ofertagames.backend.game.GameSummary(
            g.slug, g.title, g.coverUrl, MIN(o.price)
        )
        FROM Game g
        JOIN Offer o ON o.game = g
        GROUP BY g.slug, g.title, g.coverUrl
        ORDER BY g.title
    """)
    List<GameSummary> findAllWithMinPrice(Pageable pageable);
}
