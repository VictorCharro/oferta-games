package com.ofertagames.backend.conexoes;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class RepositorioConexoesXbox {
  private final JdbcClient jdbc;

  RepositorioConexoesXbox(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  void salvarConexao(String usuarioId, String xuid, String gamertag, String avatarUrl, Integer gamerscore, String token) {
    jdbc.sql("""
        INSERT INTO xbox_connections (user_id, xuid, gamertag, avatar_url, gamerscore, access_token, connected_at, last_error)
        VALUES (CAST(:usuarioId AS uuid), :xuid, :gamertag, :avatar, :gamerscore, :token, now(), NULL)
        ON CONFLICT (user_id) DO UPDATE
          SET xuid = EXCLUDED.xuid,
              gamertag = EXCLUDED.gamertag,
              avatar_url = EXCLUDED.avatar_url,
              gamerscore = EXCLUDED.gamerscore,
              access_token = EXCLUDED.access_token,
              connected_at = now(),
              last_error = NULL
        """)
        .param("usuarioId", usuarioId)
        .param("xuid", xuid)
        .param("gamertag", gamertag)
        .param("avatar", avatarUrl)
        .param("gamerscore", gamerscore)
        .param("token", token)
        .update();
  }

  Optional<ConexaoXbox> buscarConexao(String usuarioId) {
    return jdbc.sql("""
        SELECT xuid, gamertag, avatar_url, gamerscore, connected_at::text, last_library_sync_at::text, last_error
        FROM xbox_connections
        WHERE user_id = CAST(:usuarioId AS uuid)
        """)
        .param("usuarioId", usuarioId)
        .query((rs, linha) -> new ConexaoXbox(
            rs.getString("xuid"),
            rs.getString("gamertag"),
            rs.getString("avatar_url"),
            rs.getObject("gamerscore") == null ? null : rs.getInt("gamerscore"),
            rs.getString("connected_at"),
            rs.getString("last_library_sync_at"),
            rs.getString("last_error")))
        .optional();
  }

  void removerConexao(String usuarioId) {
    jdbc.sql("DELETE FROM xbox_connections WHERE user_id = CAST(:usuarioId AS uuid)")
        .param("usuarioId", usuarioId)
        .update();
  }

  record ConexaoXbox(String xuid, String gamertag, String avatarUrl, Integer gamerscore, String conectadoEm, String bibliotecaSincronizadaEm, String ultimoErro) {}
}
