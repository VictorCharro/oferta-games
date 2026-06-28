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

    @Query(value = """
        SELECT g.slug, g.title, g.cover_url AS coverUrl, MIN(o.price) AS minPrice
        FROM games g
        JOIN offers o ON o.game_id = g.id
        GROUP BY g.id, g.slug, g.title, g.cover_url
        ORDER BY g.title
    """, nativeQuery = true)
    List<GameSummaryProjection> findAllWithMinPrice(Pageable pageable);
}
