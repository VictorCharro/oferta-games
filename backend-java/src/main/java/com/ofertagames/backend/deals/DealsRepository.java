package com.ofertagames.backend.deals;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DealsRepository {
  private final JdbcClient jdbc;

  DealsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<DealDto> findTopDeals(int size, String sort) {
    String orderBy = "rank".equals(sort)
        ? "rank ASC NULLS LAST, discount_pct DESC"
        : "discount_pct DESC, rank ASC NULLS LAST";

    String sql = """
        SELECT * FROM (
          SELECT DISTINCT ON (g.id)
            g.slug,
            g.title,
            g.cover_url,
            g.is_dlc,
            g.rank,
            o.store_name,
            o.price,
            o.regular_price,
            o.url,
            ROUND((1 - o.price / o.regular_price) * 100)::integer AS discount_pct
          FROM offers o
          JOIN games g ON g.id = o.game_id
          WHERE o.regular_price IS NOT NULL
            AND o.regular_price > 0
            AND o.price < o.regular_price
            AND o.price < o.regular_price * 0.99
          ORDER BY g.id, o.price ASC
        ) sub
        ORDER BY %s
        LIMIT :size
        """.formatted(orderBy);

    return jdbc.sql(sql)
        .param("size", size)
        .query((rs, rowNum) -> new DealDto(
            rs.getString("slug"),
            rs.getString("title"),
            rs.getString("cover_url"),
            rs.getObject("is_dlc", Boolean.class),
            rs.getObject("rank", Integer.class),
            rs.getString("store_name"),
            rs.getBigDecimal("price"),
            rs.getBigDecimal("regular_price"),
            rs.getString("url"),
            rs.getObject("discount_pct", Integer.class)))
        .list();
  }
}
