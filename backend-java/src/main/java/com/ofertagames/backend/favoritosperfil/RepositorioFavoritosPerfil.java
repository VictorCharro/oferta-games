package com.ofertagames.backend.favoritosperfil;

import com.ofertagames.backend.comum.ConteudosNaoJogos;
import com.ofertagames.backend.comum.JogosBloqueados;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Favoritos do perfil publico (dois tipos: jogo do catalogo via slug, ou jogo da biblioteca Steam
 * via app_id). {@code profile_favorites}/{@code profile_steam_favorites} moram no Supabase (tem
 * FK com {@code auth.users}); {@code games}/{@code offers} moram no Postgres do catalogo, na VM —
 * por isso {@code listarPorUsuario} e a parte de slug de {@code reordenar} buscam num banco e
 * junta no outro em Java, em vez de um JOIN SQL so (que so funcionava quando os dois estavam no
 * mesmo Postgres, antes da migracao do catalogo pra fora do Supabase).
 */
@Repository
public class RepositorioFavoritosPerfil {
  // Proxima posicao livre considerando as duas tabelas de favoritos do usuario (novos itens vao para o fim).
  private static final String SQL_PROXIMA_POSICAO = """
      (SELECT COALESCE(MAX(position), -1) + 1 FROM (
         SELECT position FROM profile_favorites WHERE user_id = CAST(:usuarioId AS uuid)
         UNION ALL
         SELECT position FROM profile_steam_favorites WHERE user_id = CAST(:usuarioId AS uuid)
       ) t)""";

  private final JdbcClient jdbc;
  private final JdbcClient jdbcCatalogo;

  RepositorioFavoritosPerfil(JdbcClient jdbc, @Qualifier("catalogo") JdbcClient jdbcCatalogo) {
    this.jdbc = jdbc;
    this.jdbcCatalogo = jdbcCatalogo;
  }

  public List<FavoritoPerfilJogo> listarPorUsuario(String usuarioId) {
    List<FavoritoDeJogo> favoritosDeJogo = jdbc.sql("""
        SELECT game_id, position, created_at::text AS favorited_at
        FROM profile_favorites
        WHERE user_id = CAST(:usuarioId AS uuid)
        """)
        .param("usuarioId", usuarioId)
        .query((rs, linha) -> new FavoritoDeJogo(rs.getLong("game_id"), rs.getInt("position"), rs.getString("favorited_at")))
        .list();

    Map<Long, DadosJogoCatalogo> dadosPorJogo = buscarDadosCatalogo(
        favoritosDeJogo.stream().map(FavoritoDeJogo::gameId).toList());

    List<ItemComOrdem> itens = new ArrayList<>();
    for (FavoritoDeJogo f : favoritosDeJogo) {
      DadosJogoCatalogo d = dadosPorJogo.get(f.gameId());
      // Jogo favoritado que sumiu do catalogo (bloqueado, virou conteudo-nao-jogo) nao aparece
      // mais - mesmo comportamento do JOIN antigo, que simplesmente nao devolvia a linha.
      if (d == null) {
        continue;
      }
      itens.add(new ItemComOrdem(
          f.position(),
          f.favoritedAt(),
          new FavoritoPerfilJogo(
              d.slug(), null, d.title(), d.coverUrl(), null, d.isDlc(), null, null, null,
              d.minPrice(), d.regularPrice(), f.favoritedAt())));
    }

    itens.addAll(jdbc.sql("""
        SELECT
          b.app_id AS steam_app_id,
          b.title,
          b.cover_url,
          b.icon_hash,
          b.playtime_minutes,
          COALESCE(a.unlocked_count, 0) AS unlocked_count,
          COALESCE(a.total_count, 0) AS total_count,
          f.created_at::text AS favorited_at,
          f.position AS position
        FROM profile_steam_favorites f
        JOIN steam_library_games b ON b.user_id = f.user_id AND b.app_id = f.app_id
        LEFT JOIN steam_game_achievements a ON a.user_id = b.user_id AND a.app_id = b.app_id
        WHERE f.user_id = CAST(:usuarioId AS uuid)
        """)
        .param("usuarioId", usuarioId)
        .query((rs, linha) -> {
          int posicao = rs.getInt("position");
          String favoritedAt = rs.getString("favorited_at");
          return new ItemComOrdem(posicao, favoritedAt, new FavoritoPerfilJogo(
              null,
              rs.getObject("steam_app_id", Integer.class),
              rs.getString("title"),
              rs.getString("cover_url"),
              rs.getString("icon_hash"),
              null,
              rs.getObject("playtime_minutes", Integer.class),
              rs.getObject("unlocked_count", Integer.class),
              rs.getObject("total_count", Integer.class),
              null,
              null,
              favoritedAt));
        })
        .list());

    return itens.stream()
        .sorted((a, b) -> a.position() != b.position()
            ? Integer.compare(a.position(), b.position())
            : b.favoritedAt().compareTo(a.favoritedAt()))
        .map(ItemComOrdem::item)
        .toList();
  }

  private Map<Long, DadosJogoCatalogo> buscarDadosCatalogo(List<Long> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<Long, DadosJogoCatalogo> resultado = new HashMap<>();
    for (DadosJogoCatalogo d : jdbcCatalogo.sql("""
        SELECT
          g.id,
          g.slug,
          g.title,
          g.cover_url,
          g.is_dlc,
          oferta.price AS min_price,
          oferta.regular_price AS regular_price
        FROM games g
        LEFT JOIN LATERAL (
          SELECT price, regular_price
          FROM offers
          WHERE game_id = g.id
          ORDER BY price ASC
          LIMIT 1
        ) oferta ON true
        WHERE g.id IN (:ids)
          %s
          %s
        """.formatted(ConteudosNaoJogos.filtroSql("g"), JogosBloqueados.filtroSql("g")))
        .param("ids", ids)
        .query((rs, linha) -> new DadosJogoCatalogo(
            rs.getLong("id"),
            rs.getString("slug"),
            rs.getString("title"),
            rs.getString("cover_url"),
            rs.getObject("is_dlc", Boolean.class),
            rs.getBigDecimal("min_price"),
            rs.getBigDecimal("regular_price")))
        .list()) {
      resultado.put(d.id(), d);
    }
    return resultado;
  }

  public boolean adicionar(String usuarioId, long jogoId) {
    return jdbc.sql("""
        INSERT INTO profile_favorites (user_id, game_id, position)
        VALUES (CAST(:usuarioId AS uuid), :jogoId, %s)
        ON CONFLICT (user_id, game_id) DO NOTHING
        """.formatted(SQL_PROXIMA_POSICAO))
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
        INSERT INTO profile_steam_favorites (user_id, app_id, position)
        SELECT user_id, app_id, %s
        FROM steam_library_games
        WHERE user_id = CAST(:usuarioId AS uuid) AND app_id = :appId
        ON CONFLICT (user_id, app_id) DO NOTHING
        """.formatted(SQL_PROXIMA_POSICAO))
        .param("usuarioId", usuarioId)
        .param("appId", appId)
        .update() > 0;
  }

  public void reordenar(String usuarioId, List<RequisicaoOrdemFavoritos.ItemOrdemFavorito> itens) {
    if (itens == null) return;

    // Resolve todos os slugs pro game_id de uma vez no banco de catalogo, antes de atualizar
    // profile_favorites (Supabase) - sem isso seria um UPDATE ... FROM games, que exigia os dois
    // estarem no mesmo Postgres.
    List<String> slugs = itens.stream()
        .filter(item -> item != null && item.slug() != null && !item.slug().isBlank())
        .map(RequisicaoOrdemFavoritos.ItemOrdemFavorito::slug)
        .toList();
    Map<String, Long> idPorSlug = buscarIdsPorSlug(slugs);

    int posicao = 0;
    for (RequisicaoOrdemFavoritos.ItemOrdemFavorito item : itens) {
      if (item == null) continue;
      if (item.slug() != null && !item.slug().isBlank()) {
        Long jogoId = idPorSlug.get(item.slug());
        if (jogoId == null) continue;
        jdbc.sql("""
            UPDATE profile_favorites
            SET position = :posicao
            WHERE user_id = CAST(:usuarioId AS uuid) AND game_id = :jogoId
            """)
            .param("posicao", posicao)
            .param("usuarioId", usuarioId)
            .param("jogoId", jogoId)
            .update();
      } else if (item.steamAppId() != null) {
        jdbc.sql("UPDATE profile_steam_favorites SET position = :posicao WHERE user_id = CAST(:usuarioId AS uuid) AND app_id = :appId")
            .param("posicao", posicao)
            .param("usuarioId", usuarioId)
            .param("appId", item.steamAppId())
            .update();
      } else {
        continue;
      }
      posicao++;
    }
  }

  private Map<String, Long> buscarIdsPorSlug(List<String> slugs) {
    if (slugs.isEmpty()) {
      return Map.of();
    }
    Map<String, Long> resultado = new HashMap<>();
    for (var linha : jdbcCatalogo.sql("SELECT id, slug FROM games WHERE slug IN (:slugs)")
        .param("slugs", slugs)
        .query((rs, l) -> Map.entry(rs.getString("slug"), rs.getLong("id")))
        .list()) {
      resultado.put(linha.getKey(), linha.getValue());
    }
    return resultado;
  }

  public boolean removerSteam(String usuarioId, int appId) {
    return jdbc.sql("DELETE FROM profile_steam_favorites WHERE user_id = CAST(:usuarioId AS uuid) AND app_id = :appId")
        .param("usuarioId", usuarioId)
        .param("appId", appId)
        .update() > 0;
  }

  public Optional<String> tituloSteam(String usuarioId, int appId) {
    return jdbc.sql("SELECT title FROM steam_library_games WHERE user_id = CAST(:usuarioId AS uuid) AND app_id = :appId")
        .param("usuarioId", usuarioId)
        .param("appId", appId)
        .query(String.class)
        .optional();
  }

  private record FavoritoDeJogo(long gameId, int position, String favoritedAt) {}

  private record DadosJogoCatalogo(
      long id, String slug, String title, String coverUrl, Boolean isDlc, BigDecimal minPrice, BigDecimal regularPrice) {}

  private record ItemComOrdem(int position, String favoritedAt, FavoritoPerfilJogo item) {}
}
