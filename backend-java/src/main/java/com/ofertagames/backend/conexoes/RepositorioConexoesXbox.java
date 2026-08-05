package com.ofertagames.backend.conexoes;

import java.util.List;
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

  Optional<String> buscarToken(String usuarioId) {
    return jdbc.sql("SELECT access_token FROM xbox_connections WHERE user_id = CAST(:usuarioId AS uuid)")
        .param("usuarioId", usuarioId)
        .query(String.class)
        .optional();
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
    // So remove o vinculo de login - a biblioteca ja sincronizada (xbox_library_games) fica,
    // pra nao apagar historico do usuario so por desconectar a conta.
    jdbc.sql("DELETE FROM xbox_connections WHERE user_id = CAST(:usuarioId AS uuid)")
        .param("usuarioId", usuarioId)
        .update();
  }

  // Upsert por jogo: nunca faz DELETE da biblioteca existente, so atualiza/adiciona.
  void upsertJogoBiblioteca(String usuarioId, String titleId, String titulo, String capaUrl,
      int conquistasDesbloqueadas, int conquistasTotal, int gamerscoreDesbloqueado, int gamerscoreTotal, String ultimaJogada) {
    jdbc.sql("""
        INSERT INTO xbox_library_games (user_id, title_id, title, cover_url, achievements_unlocked, achievements_total,
            gamerscore_unlocked, gamerscore_total, last_played_at, last_synced_at)
        VALUES (CAST(:usuarioId AS uuid), :titleId, :titulo, :capa, :conqDesb, :conqTotal, :gsDesb, :gsTotal,
            CAST(:ultimaJogada AS timestamptz), now())
        ON CONFLICT (user_id, title_id) DO UPDATE
          SET title = EXCLUDED.title,
              cover_url = EXCLUDED.cover_url,
              achievements_unlocked = EXCLUDED.achievements_unlocked,
              achievements_total = EXCLUDED.achievements_total,
              gamerscore_unlocked = EXCLUDED.gamerscore_unlocked,
              gamerscore_total = EXCLUDED.gamerscore_total,
              last_played_at = EXCLUDED.last_played_at,
              last_synced_at = now()
        """)
        .param("usuarioId", usuarioId)
        .param("titleId", titleId)
        .param("titulo", titulo)
        .param("capa", capaUrl)
        .param("conqDesb", conquistasDesbloqueadas)
        .param("conqTotal", conquistasTotal)
        .param("gsDesb", gamerscoreDesbloqueado)
        .param("gsTotal", gamerscoreTotal)
        .param("ultimaJogada", ultimaJogada)
        .update();
  }

  void marcarBibliotecaSincronizada(String usuarioId) {
    jdbc.sql("UPDATE xbox_connections SET last_library_sync_at = now() WHERE user_id = CAST(:usuarioId AS uuid)")
        .param("usuarioId", usuarioId)
        .update();
  }

  List<JogoBibliotecaXbox> listarBiblioteca(String usuarioId) {
    return jdbc.sql("""
        SELECT title_id, title, cover_url, achievements_unlocked, achievements_total,
               gamerscore_unlocked, gamerscore_total, last_played_at::text
        FROM xbox_library_games
        WHERE user_id = CAST(:usuarioId AS uuid)
        ORDER BY last_played_at DESC NULLS LAST
        """)
        .param("usuarioId", usuarioId)
        .query((rs, linha) -> new JogoBibliotecaXbox(
            rs.getString("title_id"),
            rs.getString("title"),
            rs.getString("cover_url"),
            rs.getInt("achievements_unlocked"),
            rs.getInt("achievements_total"),
            rs.getInt("gamerscore_unlocked"),
            rs.getInt("gamerscore_total"),
            rs.getString("last_played_at")))
        .list();
  }

  record ConexaoXbox(String xuid, String gamertag, String avatarUrl, Integer gamerscore, String conectadoEm, String bibliotecaSincronizadaEm, String ultimoErro) {}
  record JogoBibliotecaXbox(String titleId, String titulo, String capaUrl, int conquistasDesbloqueadas, int conquistasTotal, int gamerscoreDesbloqueado, int gamerscoreTotal, String ultimaJogada) {}
}
