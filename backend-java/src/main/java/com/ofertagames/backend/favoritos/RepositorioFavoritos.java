package com.ofertagames.backend.favoritos;

import com.ofertagames.backend.comum.ConteudosNaoJogos;
import com.ofertagames.backend.comum.JogosBloqueados;
import com.ofertagames.backend.comum.LojasBloqueadas;
import java.math.BigDecimal;
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
          oferta.store_name AS store_name,
          f.target_price AS target_price,
          f.created_at::text AS favorited_at
        FROM favorites f
        JOIN games g ON g.id = f.game_id
        LEFT JOIN LATERAL (
          SELECT price, regular_price, store_name
          FROM offers o
          WHERE o.game_id = g.id
            %s
          ORDER BY price ASC
          LIMIT 1
        ) oferta ON true
        WHERE f.user_id = CAST(:usuarioId AS uuid)
          %s
          %s
        ORDER BY f.created_at DESC
        """.formatted(LojasBloqueadas.filtroSql("o"), ConteudosNaoJogos.filtroSql("g"), JogosBloqueados.filtroSql("g")))
        .param("usuarioId", usuarioId)
        .query((rs, linha) -> new FavoritoJogo(
            rs.getString("slug"),
            rs.getString("title"),
            rs.getString("cover_url"),
            rs.getObject("is_dlc", Boolean.class),
            rs.getBigDecimal("min_price"),
            rs.getBigDecimal("regular_price"),
            rs.getString("store_name"),
            rs.getBigDecimal("target_price"),
            rs.getString("favorited_at")))
        .list();
  }

  // Upsert: se ja estava monitorando, so atualiza a meta. O "xmax = 0" e o jeito Postgres de
  // distinguir insert de update dentro do ON CONFLICT - usado pra so registrar a atividade de
  // "monitoramento adicionado" quando o jogo e novo, nao toda vez que o usuario so edita a meta.
  public boolean adicionar(String usuarioId, long jogoId, BigDecimal metaPreco) {
    return jdbc.sql("""
        INSERT INTO favorites (user_id, game_id, target_price)
        VALUES (CAST(:usuarioId AS uuid), :jogoId, :metaPreco)
        ON CONFLICT (user_id, game_id) DO UPDATE SET target_price = EXCLUDED.target_price
        RETURNING (xmax = 0) AS inserted
        """)
        .param("usuarioId", usuarioId)
        .param("jogoId", jogoId)
        .param("metaPreco", metaPreco)
        .query(Boolean.class)
        .single();
  }

  public boolean remover(String usuarioId, long jogoId) {
    return jdbc.sql("DELETE FROM favorites WHERE user_id = CAST(:usuarioId AS uuid) AND game_id = :jogoId")
        .param("usuarioId", usuarioId)
        .param("jogoId", jogoId)
        .update() > 0;
  }
}
