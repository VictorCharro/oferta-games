package com.ofertagames.backend.favoritosperfil;

import com.ofertagames.backend.comum.ConteudosNaoJogos;
import com.ofertagames.backend.comum.JogosBloqueados;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RepositorioFavoritosPerfil {
  private final JdbcClient jdbc;

  RepositorioFavoritosPerfil(JdbcClient jdbc) { this.jdbc = jdbc; }

  public List<FavoritoPerfilJogo> listarPorUsuario(String usuarioId) {
    return jdbc.sql("""
        SELECT
          g.slug,
          NULL::integer AS steam_app_id,
          g.title,
          g.cover_url,
          NULL::text AS icon_hash,
          g.is_dlc,
          oferta.price AS min_price,
          oferta.regular_price AS regular_price,
          f.created_at::text AS favorited_at
        FROM profile_favorites f
        JOIN games g ON g.id = f.game_id
        LEFT JOIN LATERAL (
          SELECT price, regular_price
          FROM offers
          WHERE game_id = g.id
          ORDER BY price ASC
          LIMIT 1
        ) oferta ON true
        WHERE f.user_id = CAST(:usuarioId AS uuid)
          %s
          %s
        UNION ALL
        SELECT
          NULL::text AS slug,
          b.app_id AS steam_app_id,
          b.title,
          NULL::text AS cover_url,
          b.icon_hash,
          NULL::boolean AS is_dlc,
          NULL::numeric AS min_price,
          NULL::numeric AS regular_price,
          f.created_at::text AS favorited_at
        FROM profile_steam_favorites f
        JOIN steam_library_games b ON b.user_id = f.user_id AND b.app_id = f.app_id
        WHERE f.user_id = CAST(:usuarioId AS uuid)
        ORDER BY favorited_at DESC
        """.formatted(ConteudosNaoJogos.filtroSql("g"), JogosBloqueados.filtroSql("g")))
        .param("usuarioId", usuarioId)
        .query((rs, linha) -> new FavoritoPerfilJogo(
            rs.getString("slug"),
            rs.getObject("steam_app_id", Integer.class),
            rs.getString("title"),
            rs.getString("cover_url"),
            rs.getString("icon_hash"),
            rs.getObject("is_dlc", Boolean.class),
            rs.getBigDecimal("min_price"),
            rs.getBigDecimal("regular_price"),
            rs.getString("favorited_at")))
        .list();
  }

  public boolean adicionar(String usuarioId, long jogoId) {
    return jdbc.sql("""
        INSERT INTO profile_favorites (user_id, game_id)
        VALUES (CAST(:usuarioId AS uuid), :jogoId)
        ON CONFLICT (user_id, game_id) DO NOTHING
        """)
        .param("usuarioId", usuarioId)
        .param("jogoId", jogoId)
        .update() > 0;
  }

  public boolean remover(String usuarioId, long jogoId) {
    return jdbc.sql("DELETE FROM profile_favorites WHERE user_id = CAST(:usuarioId AS uuid) AND game_id = :jogoId")
        .param("usuarioId", usuarioId)
        .param("jogoId", jogoId)
        .update() > 0;
  }

  public boolean adicionarSteam(String usuarioId, int appId) {
    return jdbc.sql("""
        INSERT INTO profile_steam_favorites (user_id, app_id)
        SELECT user_id, app_id
        FROM steam_library_games
        WHERE user_id = CAST(:usuarioId AS uuid) AND app_id = :appId
        ON CONFLICT (user_id, app_id) DO NOTHING
        """)
        .param("usuarioId", usuarioId)
        .param("appId", appId)
        .update() > 0;
  }

  public boolean removerSteam(String usuarioId, int appId) {
    return jdbc.sql("DELETE FROM profile_steam_favorites WHERE user_id = CAST(:usuarioId AS uuid) AND app_id = :appId")
        .param("usuarioId", usuarioId)
        .param("appId", appId)
        .update() > 0;
  }

  public java.util.Optional<String> tituloSteam(String usuarioId, int appId) {
    return jdbc.sql("SELECT title FROM steam_library_games WHERE user_id = CAST(:usuarioId AS uuid) AND app_id = :appId")
        .param("usuarioId", usuarioId)
        .param("appId", appId)
        .query(String.class)
        .optional();
  }
}
