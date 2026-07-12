package com.ofertagames.backend.atividadesperfil;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RepositorioAtividadesPerfil {
  private final JdbcClient jdbc;

  RepositorioAtividadesPerfil(JdbcClient jdbc) { this.jdbc = jdbc; }

  public void registrar(String usuarioId, String tipo) { registrar(usuarioId, tipo, null); }

  public void registrar(String usuarioId, String tipo, Long jogoId) {
    jdbc.sql("INSERT INTO profile_activities (user_id, tipo, game_id) VALUES (CAST(:usuarioId AS uuid), :tipo, :jogoId)")
        .param("usuarioId", usuarioId).param("tipo", tipo).param("jogoId", jogoId).update();
  }

  public List<AtividadePerfil> listarPorUsuario(String usuarioId, int limite) {
    return jdbc.sql("""
        SELECT a.tipo, g.title AS titulo_jogo, a.created_at::text AS criada_em
        FROM profile_activities a
        LEFT JOIN games g ON g.id = a.game_id
        WHERE a.user_id = CAST(:usuarioId AS uuid)
        ORDER BY a.created_at DESC
        LIMIT :limite
        """)
        .param("usuarioId", usuarioId).param("limite", limite)
        .query((rs, linha) -> new AtividadePerfil(rs.getString("tipo"), rs.getString("titulo_jogo"), rs.getString("criada_em")))
        .list();
  }
}
