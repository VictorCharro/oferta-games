package com.ofertagames.backend.games;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class GameRepository {
  private final JdbcClient jdbc;

  GameRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<GameSummary> findGames(int page, int size, String sort, String type, Double minPrice, Double maxPrice, String q) {
    int offset = page * size;
    String typeFilter = switch (type) {
      case "dlc" -> "AND (g.is_dlc = true OR (g.is_dlc IS NULL AND " + titleIsDlcSql() + "))";
      case "game" -> "AND (g.is_dlc = false OR (g.is_dlc IS NULL AND NOT " + titleIsDlcSql() + "))";
      default -> "";
    };
    String searchFilter = q == null || q.isBlank() ? "" : "AND g.title ILIKE :q";
    String having = buildPriceHaving(minPrice, maxPrice);

    String sql = """
        SELECT
          g.slug,
          g.title,
          g.cover_url AS cover_url,
          g.is_dlc AS is_dlc,
          MIN(o.price) AS min_price,
          MAX(o.regular_price) AS regular_price
        FROM games g
        LEFT JOIN offers o ON o.game_id = g.id
        WHERE 1=1
        %s
        %s
        GROUP BY g.id, g.slug, g.title, g.cover_url, g.is_dlc
        %s
        ORDER BY %s
        LIMIT :size OFFSET :offset
        """.formatted(typeFilter, searchFilter, having, orderBy(sort));

    var statement = jdbc.sql(sql)
        .param("size", size)
        .param("offset", offset);
    if (q != null && !q.isBlank()) {
      statement = statement.param("q", "%" + q.trim() + "%");
    }
    if (minPrice != null) {
      statement = statement.param("minPrice", minPrice);
    }
    if (maxPrice != null) {
      statement = statement.param("maxPrice", maxPrice);
    }

    return statement.query(GameRepository::mapSummary).list();
  }

  public Optional<GameDetail> findBySlug(String slug) {
    Optional<GameRow> game = jdbc.sql("""
        SELECT id, slug, title, cover_url
        FROM games
        WHERE slug = :slug
        """)
        .param("slug", slug)
        .query((rs, rowNum) -> new GameRow(
            rs.getLong("id"),
            rs.getString("slug"),
            rs.getString("title"),
            rs.getString("cover_url")))
        .optional();

    return game.map(row -> new GameDetail(row.id(), row.slug(), row.title(), row.coverUrl(), findOffers(row.id())));
  }

  public Optional<Long> findIdBySlug(String slug) {
    return jdbc.sql("SELECT id FROM games WHERE slug = :slug")
        .param("slug", slug)
        .query(Long.class)
        .optional();
  }

  private List<OfferDto> findOffers(long gameId) {
    return jdbc.sql("""
        SELECT store_name, price, regular_price, currency, url
        FROM offers
        WHERE game_id = :gameId
        ORDER BY price ASC
        """)
        .param("gameId", gameId)
        .query((rs, rowNum) -> new OfferDto(
            rs.getString("store_name"),
            rs.getBigDecimal("price"),
            rs.getBigDecimal("regular_price"),
            rs.getString("currency"),
            rs.getString("url")))
        .list();
  }

  private static GameSummary mapSummary(ResultSet rs, int rowNum) throws SQLException {
    return new GameSummary(
        rs.getString("slug"),
        rs.getString("title"),
        rs.getString("cover_url"),
        rs.getObject("is_dlc", Boolean.class),
        rs.getBigDecimal("min_price"),
        rs.getBigDecimal("regular_price"));
  }

  private static String buildPriceHaving(Double minPrice, Double maxPrice) {
    if (minPrice != null && maxPrice != null) {
      return "HAVING MIN(o.price) >= :minPrice AND MIN(o.price) <= :maxPrice";
    }
    if (minPrice != null) {
      return "HAVING MIN(o.price) >= :minPrice";
    }
    if (maxPrice != null) {
      return "HAVING MIN(o.price) <= :maxPrice";
    }
    return "";
  }

  private static String orderBy(String sort) {
    return switch (sort) {
      case "discount" -> "ROUND((1 - MIN(o.price) / NULLIF(MAX(o.regular_price), 0)) * 100) DESC NULLS LAST, g.rank ASC NULLS LAST";
      case "price_asc" -> "MIN(o.price) ASC NULLS LAST";
      case "price_desc" -> "MIN(o.price) DESC NULLS LAST";
      default -> """
          CASE
            WHEN g.rank <= 200
              AND MIN(o.price) IS NOT NULL
              AND MAX(o.regular_price) IS NOT NULL
              AND MIN(o.price) < MAX(o.regular_price) * 0.99
            THEN 0 ELSE 1
          END ASC,
          CASE
            WHEN g.rank <= 200
              AND MIN(o.price) IS NOT NULL
              AND MAX(o.regular_price) IS NOT NULL
              AND MIN(o.price) < MAX(o.regular_price) * 0.99
            THEN ROUND((1 - MIN(o.price) / NULLIF(MAX(o.regular_price), 0)) * 100)
            ELSE NULL
          END DESC NULLS LAST,
          g.rank ASC NULLS LAST,
          g.id ASC
          """;
    };
  }

  private static String titleIsDlcSql() {
    return """
        (g.title ILIKE '%DLC%'
          OR g.title ILIKE '%Season Pass%'
          OR g.title ILIKE '%Soundtrack%'
          OR g.title ILIKE '% OST%'
          OR g.title ILIKE '%Art Book%'
          OR g.title ILIKE '%Skin Set%'
          OR g.title ILIKE '%Skin Pack%'
          OR g.title ILIKE '%Booster Pack%'
          OR g.title ILIKE '%Expansion%'
          OR g.title ILIKE '%Add-on%')
        """;
  }

  private record GameRow(Long id, String slug, String title, String coverUrl) {}
}
