package com.ofertagames.backend.favorites;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FavoritesRepository {
  private final JdbcClient jdbc;

  FavoritesRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<FavoriteDto> findByUser(String userId) {
    return jdbc.sql("""
        SELECT
          g.slug,
          g.title,
          g.cover_url,
          MIN(o.price) AS min_price,
          MAX(o.regular_price) AS regular_price,
          f.created_at AS favorited_at
        FROM favorites f
        JOIN games g ON g.id = f.game_id
        LEFT JOIN offers o ON o.game_id = g.id
        WHERE f.user_id = CAST(:userId AS uuid)
        GROUP BY g.id, g.slug, g.title, g.cover_url, f.created_at
        ORDER BY f.created_at DESC
        """)
        .param("userId", userId)
        .query((rs, rowNum) -> new FavoriteDto(
            rs.getString("slug"),
            rs.getString("title"),
            rs.getString("cover_url"),
            rs.getBigDecimal("min_price"),
            rs.getBigDecimal("regular_price"),
            rs.getObject("favorited_at", java.time.OffsetDateTime.class)))
        .list();
  }

  public void add(String userId, long gameId) {
    jdbc.sql("""
        INSERT INTO favorites (user_id, game_id)
        VALUES (CAST(:userId AS uuid), :gameId)
        ON CONFLICT (user_id, game_id) DO NOTHING
        """)
        .param("userId", userId)
        .param("gameId", gameId)
        .update();
  }

  public void delete(String userId, long gameId) {
    jdbc.sql("DELETE FROM favorites WHERE user_id = CAST(:userId AS uuid) AND game_id = :gameId")
        .param("userId", userId)
        .param("gameId", gameId)
        .update();
  }
}
