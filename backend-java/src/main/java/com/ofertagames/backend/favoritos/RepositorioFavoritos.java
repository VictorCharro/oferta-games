package com.ofertagames.backend.favoritos;

import com.ofertagames.backend.comum.ConteudosNaoJogos;
import com.ofertagames.backend.comum.JogosBloqueados;
import com.ofertagames.backend.comum.LojasBloqueadas;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Favoritos com alerta de preco (distinto do favorito de perfil, ver
 * {@link com.ofertagames.backend.favoritosperfil.RepositorioFavoritosPerfil}). {@code favorites}
 * mora no Supabase (FK com {@code auth.users}); {@code games}/{@code offers} moram no Postgres do
 * catalogo, na VM - {@code listarPorUsuario} busca nos dois bancos e junta em Java.
 */
@Repository
public class RepositorioFavoritos {
  private final JdbcClient jdbc;
  private final JdbcClient jdbcCatalogo;

  RepositorioFavoritos(JdbcClient jdbc, @Qualifier("catalogo") JdbcClient jdbcCatalogo) {
    this.jdbc = jdbc;
    this.jdbcCatalogo = jdbcCatalogo;
  }

  public List<FavoritoJogo> listarPorUsuario(String usuarioId) {
    List<FavoritoComMeta> favoritos = jdbc.sql("""
        SELECT game_id, target_price, created_at::text AS favorited_at
        FROM favorites
        WHERE user_id = CAST(:usuarioId AS uuid)
        ORDER BY created_at DESC
        """)
        .param("usuarioId", usuarioId)
        .query((rs, linha) -> new FavoritoComMeta(
            rs.getLong("game_id"), rs.getBigDecimal("target_price"), rs.getString("favorited_at")))
        .list();

    Map<Long, DadosJogoCatalogo> dadosPorJogo = buscarDadosCatalogo(
        favoritos.stream().map(FavoritoComMeta::gameId).toList());

    // A ordem ja veio certa do ORDER BY created_at DESC acima - so preserva ao montar a lista
    // final, pulando jogos que sumiram do catalogo (bloqueado, virou conteudo-nao-jogo).
    List<FavoritoJogo> resultado = new ArrayList<>();
    for (FavoritoComMeta f : favoritos) {
      DadosJogoCatalogo d = dadosPorJogo.get(f.gameId());
      if (d == null) continue;
      resultado.add(new FavoritoJogo(
          d.slug(), d.title(), d.coverUrl(), d.isDlc(),
          d.minPrice(), d.regularPrice(), d.storeName(), f.targetPrice(), f.favoritedAt()));
    }
    return resultado;
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
          oferta.regular_price AS regular_price,
          oferta.store_name AS store_name
        FROM games g
        LEFT JOIN LATERAL (
          SELECT price, regular_price, store_name
          FROM offers o
          WHERE o.game_id = g.id
            %s
          ORDER BY price ASC
          LIMIT 1
        ) oferta ON true
        WHERE g.id IN (:ids)
          %s
          %s
        """.formatted(LojasBloqueadas.filtroSql("o"), ConteudosNaoJogos.filtroSql("g"), JogosBloqueados.filtroSql("g")))
        .param("ids", ids)
        .query((rs, linha) -> new DadosJogoCatalogo(
            rs.getLong("id"),
            rs.getString("slug"),
            rs.getString("title"),
            rs.getString("cover_url"),
            rs.getObject("is_dlc", Boolean.class),
            rs.getBigDecimal("min_price"),
            rs.getBigDecimal("regular_price"),
            rs.getString("store_name")))
        .list()) {
      resultado.put(d.id(), d);
    }
    return resultado;
  }

  private record FavoritoComMeta(long gameId, BigDecimal targetPrice, String favoritedAt) {}

  private record DadosJogoCatalogo(
      long id, String slug, String title, String coverUrl, Boolean isDlc,
      BigDecimal minPrice, BigDecimal regularPrice, String storeName) {}

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
