package com.ofertagames.backend.conexoes;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class RepositorioConexoesSteam {
  private final JdbcClient jdbc;

  RepositorioConexoesSteam(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  void criarEstado(UUID estado, String usuarioId) {
    jdbc.sql("""
        INSERT INTO steam_auth_states (state, user_id, expires_at)
        VALUES (:estado, CAST(:usuarioId AS uuid), now() + interval '10 minutes')
        """)
        .param("estado", estado)
        .param("usuarioId", usuarioId)
        .update();
  }

  Optional<String> consumirEstado(UUID estado) {
    Optional<String> usuarioId = jdbc.sql("""
        DELETE FROM steam_auth_states
        WHERE state = :estado AND expires_at > now()
        RETURNING user_id::text
        """)
        .param("estado", estado)
        .query(String.class)
        .optional();
    jdbc.sql("DELETE FROM steam_auth_states WHERE expires_at <= now()").update();
    return usuarioId;
  }

  void salvarConexao(String usuarioId, String steamId, String nome, String avatar) {
    jdbc.sql("""
        INSERT INTO steam_connections (user_id, steam_id, persona_name, avatar_url, connected_at, last_error)
        VALUES (CAST(:usuarioId AS uuid), :steamId, :nome, :avatar, now(), NULL)
        ON CONFLICT (user_id) DO UPDATE
          SET steam_id = EXCLUDED.steam_id,
              persona_name = EXCLUDED.persona_name,
              avatar_url = EXCLUDED.avatar_url,
              connected_at = now(),
              last_error = NULL
        """)
        .param("usuarioId", usuarioId)
        .param("steamId", steamId)
        .param("nome", nome)
        .param("avatar", avatar)
        .update();
  }

  Optional<ConexaoSteam> buscarConexao(String usuarioId) {
    return jdbc.sql("""
        SELECT steam_id, persona_name, avatar_url, connected_at::text, last_library_sync_at::text,
               last_achievement_sync_at::text, last_error
        FROM steam_connections
        WHERE user_id = CAST(:usuarioId AS uuid)
        """)
        .param("usuarioId", usuarioId)
        .query((rs, linha) -> new ConexaoSteam(
            rs.getString("steam_id"),
            rs.getString("persona_name"),
            rs.getString("avatar_url"),
            rs.getString("connected_at"),
            rs.getString("last_library_sync_at"),
            rs.getString("last_achievement_sync_at"),
            rs.getString("last_error")))
        .optional();
  }

  List<ConexaoUsuarioSteam> listarConexoes() {
    return jdbc.sql("SELECT user_id::text, steam_id FROM steam_connections ORDER BY connected_at ASC")
        .query((rs, linha) -> new ConexaoUsuarioSteam(rs.getString("user_id"), rs.getString("steam_id")))
        .list();
  }

  void substituirBiblioteca(String usuarioId, List<JogoBibliotecaSteam> jogos) {
    jdbc.sql("DELETE FROM steam_library_games WHERE user_id = CAST(:usuarioId AS uuid)")
        .param("usuarioId", usuarioId)
        .update();
    for (JogoBibliotecaSteam jogo : jogos) {
      jdbc.sql("""
          INSERT INTO steam_library_games (user_id, app_id, title, playtime_minutes, icon_hash, last_synced_at)
          VALUES (CAST(:usuarioId AS uuid), :appId, :titulo, :minutos, :icone, now())
          """)
          .param("usuarioId", usuarioId)
          .param("appId", jogo.appId())
          .param("titulo", jogo.titulo())
          .param("minutos", jogo.minutosJogadas())
          .param("icone", jogo.iconeHash())
          .update();
    }
    jdbc.sql("UPDATE steam_connections SET last_library_sync_at = now(), last_error = NULL WHERE user_id = CAST(:usuarioId AS uuid)")
        .param("usuarioId", usuarioId)
        .update();
  }

  List<JogoBibliotecaSteam> listarParaConquistas(String usuarioId, int limite) {
    return jdbc.sql("""
        SELECT b.app_id, b.title, b.playtime_minutes, b.icon_hash
        FROM steam_library_games b
        LEFT JOIN steam_game_achievements a ON a.user_id = b.user_id AND a.app_id = b.app_id
        WHERE b.user_id = CAST(:usuarioId AS uuid)
        ORDER BY a.last_synced_at ASC NULLS FIRST, b.playtime_minutes DESC, b.app_id ASC
        LIMIT :limite
        """)
        .param("usuarioId", usuarioId)
        .param("limite", limite)
        .query((rs, linha) -> new JogoBibliotecaSteam(
            rs.getInt("app_id"),
            rs.getString("title"),
            rs.getInt("playtime_minutes"),
            rs.getString("icon_hash")))
        .list();
  }

  void salvarConquistas(String usuarioId, int appId, int desbloqueadas, int total) {
    jdbc.sql("""
        INSERT INTO steam_game_achievements (user_id, app_id, unlocked_count, total_count, last_synced_at)
        VALUES (CAST(:usuarioId AS uuid), :appId, :desbloqueadas, :total, now())
        ON CONFLICT (user_id, app_id) DO UPDATE
          SET unlocked_count = EXCLUDED.unlocked_count,
              total_count = EXCLUDED.total_count,
              last_synced_at = EXCLUDED.last_synced_at
        """)
        .param("usuarioId", usuarioId)
        .param("appId", appId)
        .param("desbloqueadas", desbloqueadas)
        .param("total", total)
        .update();
  }

  void marcarConquistasSincronizadas(String usuarioId) {
    jdbc.sql("UPDATE steam_connections SET last_achievement_sync_at = now() WHERE user_id = CAST(:usuarioId AS uuid)")
        .param("usuarioId", usuarioId)
        .update();
  }

  void registrarErro(String usuarioId, String erro) {
    jdbc.sql("UPDATE steam_connections SET last_error = :erro WHERE user_id = CAST(:usuarioId AS uuid)")
        .param("usuarioId", usuarioId)
        .param("erro", erro)
        .update();
  }

  void removerConexao(String usuarioId) {
    jdbc.sql("DELETE FROM steam_connections WHERE user_id = CAST(:usuarioId AS uuid)")
        .param("usuarioId", usuarioId)
        .update();
  }

  ResumoSteam resumir(String usuarioId) {
    return jdbc.sql("""
        SELECT
          COUNT(b.app_id) AS total_jogos,
          COALESCE(SUM(b.playtime_minutes), 0) AS total_minutos,
          COALESCE(SUM(a.unlocked_count), 0) AS conquistas_desbloqueadas,
          COALESCE(SUM(a.total_count), 0) AS conquistas_total
        FROM steam_connections c
        LEFT JOIN steam_library_games b ON b.user_id = c.user_id
        LEFT JOIN steam_game_achievements a ON a.user_id = b.user_id AND a.app_id = b.app_id
        WHERE c.user_id = CAST(:usuarioId AS uuid)
        """)
        .param("usuarioId", usuarioId)
        .query((rs, linha) -> new ResumoSteam(
            rs.getLong("total_jogos"),
            rs.getLong("total_minutos"),
            rs.getLong("conquistas_desbloqueadas"),
            rs.getLong("conquistas_total")))
        .single();
  }

  record ConexaoSteam(String steamId, String nome, String avatarUrl, String conectadoEm, String bibliotecaSincronizadaEm, String conquistasSincronizadasEm, String ultimoErro) {}
  record ConexaoUsuarioSteam(String usuarioId, String steamId) {}
  record JogoBibliotecaSteam(int appId, String titulo, int minutosJogadas, String iconeHash) {}
  record ResumoSteam(long totalJogos, long totalMinutos, long conquistasDesbloqueadas, long conquistasTotal) {}
}
