package com.ofertagames.backend.avaliacoesjogo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class RepositorioAvaliacoesJogo {
  private static final int LIMITE_LISTAGEM = 50;

  private final JdbcClient jdbc;

  RepositorioAvaliacoesJogo(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  ResumoAvaliacoes resumo(long gameId) {
    return jdbc.sql("""
        SELECT count(*) AS total, avg(rating) AS media,
          count(*) FILTER (WHERE rating = 1) AS n1,
          count(*) FILTER (WHERE rating = 2) AS n2,
          count(*) FILTER (WHERE rating = 3) AS n3,
          count(*) FILTER (WHERE rating = 4) AS n4,
          count(*) FILTER (WHERE rating = 5) AS n5,
          count(*) FILTER (WHERE rating >= 4) AS recomenda
        FROM game_reviews
        WHERE game_id = :gameId
        """)
        .param("gameId", gameId)
        .query((rs, linha) -> {
          int total = rs.getInt("total");
          Map<Integer, Integer> distribuicao = new LinkedHashMap<>();
          for (int nota = 1; nota <= 5; nota++) {
            distribuicao.put(nota, rs.getInt("n" + nota));
          }
          java.math.BigDecimal mediaDecimal = rs.getBigDecimal("media");
          Double media = mediaDecimal == null ? null : mediaDecimal.doubleValue();
          Integer percentualRecomenda = total == 0 ? null : Math.round(rs.getInt("recomenda") * 100f / total);
          return new ResumoAvaliacoes(total, media, distribuicao, percentualRecomenda);
        })
        .single();
  }

  List<AvaliacaoJogo> listar(long gameId, String visitanteId) {
    String fragmentoVoto = visitanteId == null
        ? "NULL AS meu_voto"
        : "(SELECT util FROM game_review_votes v WHERE v.review_id = r.id AND v.user_id = CAST(:visitanteId AS uuid)) AS meu_voto";
    var comando = jdbc.sql("""
        SELECT r.id, p.display_name, p.avatar_url, r.rating, r.comentario, r.created_at,
          (SELECT count(*) FROM game_review_votes v WHERE v.review_id = r.id AND v.util = true) AS uteis,
          (SELECT count(*) FROM game_review_votes v WHERE v.review_id = r.id AND v.util = false) AS nao_uteis,
          %s
        FROM game_reviews r
        JOIN profiles p ON p.user_id = r.user_id
        WHERE r.game_id = :gameId
        ORDER BY r.created_at DESC
        LIMIT :limite
        """.formatted(fragmentoVoto))
        .param("gameId", gameId)
        .param("limite", LIMITE_LISTAGEM);
    if (visitanteId != null) {
      comando = comando.param("visitanteId", visitanteId);
    }
    return comando.query(RepositorioAvaliacoesJogo::mapear).list();
  }

  Optional<AvaliacaoJogo> buscarMinha(long gameId, String usuarioId) {
    return jdbc.sql("""
        SELECT r.id, p.display_name, p.avatar_url, r.rating, r.comentario, r.created_at,
          (SELECT count(*) FROM game_review_votes v WHERE v.review_id = r.id AND v.util = true) AS uteis,
          (SELECT count(*) FROM game_review_votes v WHERE v.review_id = r.id AND v.util = false) AS nao_uteis,
          NULL::boolean AS meu_voto
        FROM game_reviews r
        JOIN profiles p ON p.user_id = r.user_id
        WHERE r.game_id = :gameId AND r.user_id = CAST(:usuarioId AS uuid)
        """)
        .param("gameId", gameId)
        .param("usuarioId", usuarioId)
        .query(RepositorioAvaliacoesJogo::mapear)
        .optional();
  }

  long salvar(long gameId, String usuarioId, int nota, String comentario) {
    return jdbc.sql("""
        INSERT INTO game_reviews (game_id, user_id, rating, comentario, updated_at)
        VALUES (:gameId, CAST(:usuarioId AS uuid), :nota, :comentario, now())
        ON CONFLICT (game_id, user_id) DO UPDATE
          SET rating = EXCLUDED.rating, comentario = EXCLUDED.comentario, updated_at = now()
        RETURNING id
        """)
        .param("gameId", gameId)
        .param("usuarioId", usuarioId)
        .param("nota", nota)
        .param("comentario", comentario)
        .query(Long.class)
        .single();
  }

  boolean excluir(long gameId, String usuarioId) {
    return jdbc.sql("DELETE FROM game_reviews WHERE game_id = :gameId AND user_id = CAST(:usuarioId AS uuid)")
        .param("gameId", gameId)
        .param("usuarioId", usuarioId)
        .update() > 0;
  }

  boolean existeAvaliacao(long reviewId) {
    return Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS(SELECT 1 FROM game_reviews WHERE id = :reviewId)")
        .param("reviewId", reviewId)
        .query(Boolean.class)
        .single());
  }

  void votar(long reviewId, String usuarioId, boolean util) {
    jdbc.sql("""
        INSERT INTO game_review_votes (review_id, user_id, util)
        VALUES (:reviewId, CAST(:usuarioId AS uuid), :util)
        ON CONFLICT (review_id, user_id) DO UPDATE SET util = EXCLUDED.util
        """)
        .param("reviewId", reviewId)
        .param("usuarioId", usuarioId)
        .param("util", util)
        .update();
  }

  private static AvaliacaoJogo mapear(ResultSet rs, int linha) throws SQLException {
    int nota = rs.getInt("rating");
    return new AvaliacaoJogo(
        rs.getLong("id"),
        rs.getString("display_name"),
        rs.getString("avatar_url"),
        nota,
        rs.getString("comentario"),
        rs.getTimestamp("created_at").toInstant(),
        nota >= 4,
        rs.getInt("uteis"),
        rs.getInt("nao_uteis"),
        rs.getObject("meu_voto", Boolean.class));
  }
}
