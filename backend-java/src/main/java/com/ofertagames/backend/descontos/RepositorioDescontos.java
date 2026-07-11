package com.ofertagames.backend.descontos;

import com.ofertagames.backend.comum.ClassificadorDlc;
import com.ofertagames.backend.comum.ConteudosNaoJogos;
import com.ofertagames.backend.comum.LojasBloqueadas;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RepositorioDescontos {
  private final JdbcClient jdbc;

  RepositorioDescontos(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<DescontoJogo> listarMelhores(int tamanho, String ordenacao) {
    String ordenarPor = "rank".equals(ordenacao)
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
            %s
            %s
            %s
          ORDER BY g.id, o.price ASC
        ) sub
        ORDER BY %s
        LIMIT :tamanho
        """.formatted(
            LojasBloqueadas.filtroSql("o"),
            ConteudosNaoJogos.filtroSql("g"),
            ClassificadorDlc.filtroApenasJogosSql("g"),
            ordenarPor);

    return jdbc.sql(sql)
        .param("tamanho", tamanho)
        .query((rs, linha) -> new DescontoJogo(
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
