package com.ofertagames.backend.favoritos;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RepositorioFavoritos {
  private final JdbcClient jdbc;

  RepositorioFavoritos(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<FavoritoJogo> listarPorUsuario(String usuarioId) {
    return jdbc.sql("""
        SELECT
          g.slug,
          g.title,
          g.cover_url,
          g.is_dlc,
          oferta.price AS min_price,
          oferta.regular_price AS regular_price,
          f.created_at::text AS favorited_at
        FROM favorites f
        JOIN games g ON g.id = f.game_id
        LEFT JOIN LATERAL (
          SELECT price, regular_price
          FROM offers
          WHERE game_id = g.id
          ORDER BY price ASC
          LIMIT 1
        ) oferta ON true
        WHERE f.user_id = CAST(:usuarioId AS uuid)
        ORDER BY f.created_at DESC
        """)
        .param("usuarioId", usuarioId)
        .query((rs, linha) -> new FavoritoJogo(
            rs.getString("slug"),
            rs.getString("title"),
            rs.getString("cover_url"),
            rs.getObject("is_dlc", Boolean.class),
            rs.getBigDecimal("min_price"),
            rs.getBigDecimal("regular_price"),
            rs.getString("favorited_at")))
        .list();
  }

  public void adicionar(String usuarioId, long jogoId) {
    jdbc.sql("""
        INSERT INTO favorites (user_id, game_id)
        VALUES (CAST(:usuarioId AS uuid), :jogoId)
        ON CONFLICT (user_id, game_id) DO NOTHING
        """)
        .param("usuarioId", usuarioId)
        .param("jogoId", jogoId)
        .update();
  }

  public void remover(String usuarioId, long jogoId) {
    jdbc.sql("DELETE FROM favorites WHERE user_id = CAST(:usuarioId AS uuid) AND game_id = :jogoId")
        .param("usuarioId", usuarioId)
        .param("jogoId", jogoId)
        .update();
  }
}
