package com.ofertagames.backend.atividadesperfil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code profile_activities} mora no Supabase (FK com {@code auth.users}); o titulo do jogo vem
 * de {@code games}, no Postgres do catalogo, na VM - {@link #listarPorUsuario} busca nos dois
 * bancos e junta em Java.
 */
@Repository
public class RepositorioAtividadesPerfil {
  private final JdbcClient jdbc;
  private final JdbcClient jdbcCatalogo;

  RepositorioAtividadesPerfil(JdbcClient jdbc, @Qualifier("catalogo") JdbcClient jdbcCatalogo) {
    this.jdbc = jdbc;
    this.jdbcCatalogo = jdbcCatalogo;
  }

  public void registrar(String usuarioId, String tipo) { registrar(usuarioId, tipo, (Long) null); }

  public void registrar(String usuarioId, String tipo, Long jogoId) {
    registrar(usuarioId, tipo, jogoId, null);
  }

  public void registrar(String usuarioId, String tipo, String detalhe) {
    registrar(usuarioId, tipo, null, detalhe);
  }

  public void registrar(String usuarioId, String tipo, Long jogoId, String detalhe) {
    jdbc.sql("INSERT INTO profile_activities (user_id, tipo, game_id, detalhe) VALUES (CAST(:usuarioId AS uuid), :tipo, :jogoId, :detalhe)")
        .param("usuarioId", usuarioId).param("tipo", tipo).param("jogoId", jogoId).param("detalhe", detalhe).update();
  }

  public List<AtividadePerfil> listarPorUsuario(String usuarioId, int limite) {
    List<AtividadeBruta> atividades = jdbc.sql("""
        SELECT tipo, game_id, detalhe, created_at::text AS criada_em
        FROM profile_activities
        WHERE user_id = CAST(:usuarioId AS uuid)
        ORDER BY created_at DESC
        LIMIT :limite
        """)
        .param("usuarioId", usuarioId).param("limite", limite)
        .query((rs, linha) -> new AtividadeBruta(
            rs.getString("tipo"), rs.getObject("game_id", Long.class), rs.getString("detalhe"), rs.getString("criada_em")))
        .list();

    List<Long> idsDeJogo = atividades.stream().map(AtividadeBruta::gameId).filter(id -> id != null).distinct().toList();
    Map<Long, String> tituloPorJogo = buscarTitulos(idsDeJogo);

    return atividades.stream()
        .map(a -> new AtividadePerfil(a.tipo(), a.gameId() == null ? null : tituloPorJogo.get(a.gameId()), a.detalhe(), a.criadaEm()))
        .toList();
  }

  private Map<Long, String> buscarTitulos(List<Long> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<Long, String> resultado = new HashMap<>();
    for (var linha : jdbcCatalogo.sql("SELECT id, title FROM games WHERE id IN (:ids)")
        .param("ids", ids)
        .query((rs, l) -> Map.entry(rs.getLong("id"), rs.getString("title")))
        .list()) {
      resultado.put(linha.getKey(), linha.getValue());
    }
    return resultado;
  }

  private record AtividadeBruta(String tipo, Long gameId, String detalhe, String criadaEm) {}
}
