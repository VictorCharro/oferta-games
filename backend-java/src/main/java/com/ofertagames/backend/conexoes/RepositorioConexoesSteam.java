package com.ofertagames.backend.conexoes;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

  boolean reservarAtualizacaoPublica(String usuarioId) {
    return jdbc.sql("""
        UPDATE steam_connections
        SET last_public_profile_refresh_at = now()
        WHERE user_id = CAST(:usuarioId AS uuid)
          AND (last_public_profile_refresh_at IS NULL
            OR last_public_profile_refresh_at < now() - (10 * interval '1 minute'))
        RETURNING true
        """)
        .param("usuarioId", usuarioId)
        .query(Boolean.class)
        .optional()
        .orElse(false);
  }

  boolean atividadesBibliotecaInicializadas(String usuarioId) {
    return jdbc.sql("SELECT library_activity_baselined FROM steam_connections WHERE user_id = CAST(:usuarioId AS uuid)")
        .param("usuarioId", usuarioId).query(Boolean.class).optional().orElse(false);
  }

  boolean atividadesConquistasInicializadas(String usuarioId) {
    return jdbc.sql("SELECT achievement_activity_baselined FROM steam_connections WHERE user_id = CAST(:usuarioId AS uuid)")
        .param("usuarioId", usuarioId).query(Boolean.class).optional().orElse(false);
  }

  boolean atividadesConquistasIniciadas(String usuarioId) {
    return jdbc.sql("SELECT achievement_activity_started FROM steam_connections WHERE user_id = CAST(:usuarioId AS uuid)")
        .param("usuarioId", usuarioId).query(Boolean.class).optional().orElse(false);
  }

  void marcarAtividadesBibliotecaInicializadas(String usuarioId) {
    jdbc.sql("UPDATE steam_connections SET library_activity_baselined = true WHERE user_id = CAST(:usuarioId AS uuid)")
        .param("usuarioId", usuarioId).update();
  }

  void marcarAtividadesConquistasInicializadas(String usuarioId) {
    jdbc.sql("UPDATE steam_connections SET achievement_activity_baselined = true WHERE user_id = CAST(:usuarioId AS uuid)")
        .param("usuarioId", usuarioId).update();
  }

  void marcarAtividadesConquistasIniciadas(String usuarioId) {
    jdbc.sql("UPDATE steam_connections SET achievement_activity_started = true WHERE user_id = CAST(:usuarioId AS uuid)")
        .param("usuarioId", usuarioId).update();
  }

  List<ConexaoUsuarioSteam> listarConexoes() {
    return jdbc.sql("SELECT user_id::text, steam_id FROM steam_connections ORDER BY connected_at ASC")
        .query((rs, linha) -> new ConexaoUsuarioSteam(rs.getString("user_id"), rs.getString("steam_id")))
        .list();
  }

  List<JogoBibliotecaSteam> substituirBiblioteca(String usuarioId, List<JogoBibliotecaSteam> jogos, boolean identificarNovos) {
    Set<Integer> idsAnteriores = identificarNovos ? idsBiblioteca(usuarioId) : Set.of();
    Map<Integer, String> capasExistentes = capasBiblioteca(usuarioId);
    jdbc.sql("DELETE FROM steam_library_games WHERE user_id = CAST(:usuarioId AS uuid)")
        .param("usuarioId", usuarioId)
        .update();
    for (JogoBibliotecaSteam jogo : jogos) {
      jdbc.sql("""
          INSERT INTO steam_library_games (user_id, app_id, title, playtime_minutes, icon_hash, cover_url, last_synced_at)
          VALUES (CAST(:usuarioId AS uuid), :appId, :titulo, :minutos, :icone, :capa, now())
          """)
          .param("usuarioId", usuarioId)
          .param("appId", jogo.appId())
          .param("titulo", jogo.titulo())
          .param("minutos", jogo.minutosJogadas())
          .param("icone", jogo.iconeHash())
          .param("capa", capasExistentes.get(jogo.appId()))
          .update();
    }
    jdbc.sql("UPDATE steam_connections SET last_library_sync_at = now(), last_error = NULL WHERE user_id = CAST(:usuarioId AS uuid)")
        .param("usuarioId", usuarioId)
        .update();
    return jogos.stream().filter(jogo -> !idsAnteriores.contains(jogo.appId())).toList();
  }

  private Set<Integer> idsBiblioteca(String usuarioId) {
    return new HashSet<>(jdbc.sql("SELECT app_id FROM steam_library_games WHERE user_id = CAST(:usuarioId AS uuid)")
        .param("usuarioId", usuarioId).query(Integer.class).list());
  }

  // Preserva as capas ja resolvidas ao ressincronizar a biblioteca (a sincronizacao de playtime
  // apaga e reinsere todas as linhas, entao sem isso a capa buscada via appdetails se perderia a cada sync).
  private Map<Integer, String> capasBiblioteca(String usuarioId) {
    List<CapaExistente> linhas = jdbc.sql(
        "SELECT app_id, cover_url FROM steam_library_games WHERE user_id = CAST(:usuarioId AS uuid) AND cover_url IS NOT NULL")
        .param("usuarioId", usuarioId)
        .query((rs, linha) -> new CapaExistente(rs.getInt("app_id"), rs.getString("cover_url")))
        .list();
    Map<Integer, String> capas = new HashMap<>();
    for (CapaExistente linha : linhas) {
      capas.put(linha.appId(), linha.capaUrl());
    }
    return capas;
  }

  private record CapaExistente(int appId, String capaUrl) {}

  // Jogos da biblioteca ainda sem capa resolvida, pra fila do job que busca via appdetails da Steam.
  List<JogoParaCapa> listarSemCapa(int limite) {
    return jdbc.sql("""
        SELECT user_id::text, app_id
        FROM steam_library_games
        WHERE cover_url IS NULL
        ORDER BY cover_synced_at ASC NULLS FIRST
        LIMIT :limite
        """)
        .param("limite", limite)
        .query((rs, linha) -> new JogoParaCapa(rs.getString("user_id"), rs.getInt("app_id")))
        .list();
  }

  void salvarCapa(String usuarioId, int appId, String capaUrl) {
    jdbc.sql("""
        UPDATE steam_library_games
        SET cover_url = :capa, cover_synced_at = now()
        WHERE user_id = CAST(:usuarioId AS uuid) AND app_id = :appId
        """)
        .param("capa", capaUrl)
        .param("usuarioId", usuarioId)
        .param("appId", appId)
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

  boolean existemJogosSemConquistas(String usuarioId) {
    return jdbc.sql("""
        SELECT EXISTS (
          SELECT 1 FROM steam_library_games b
          LEFT JOIN steam_game_achievements a ON a.user_id = b.user_id AND a.app_id = b.app_id
          WHERE b.user_id = CAST(:usuarioId AS uuid) AND a.app_id IS NULL
        )
        """)
        .param("usuarioId", usuarioId).query(Boolean.class).single();
  }

  List<JogoBibliotecaSteam> listarBiblioteca(String usuarioId, int limite) {
    return jdbc.sql("""
        SELECT b.app_id, b.title, b.playtime_minutes, b.icon_hash, b.cover_url,
               COALESCE(a.unlocked_count, 0) AS unlocked_count,
               COALESCE(a.total_count, 0) AS total_count
        FROM steam_library_games b
        LEFT JOIN steam_game_achievements a ON a.user_id = b.user_id AND a.app_id = b.app_id
        WHERE b.user_id = CAST(:usuarioId AS uuid)
        ORDER BY b.playtime_minutes DESC, b.title ASC
        LIMIT :limite
        """)
        .param("usuarioId", usuarioId)
        .param("limite", limite)
        .query((rs, linha) -> new JogoBibliotecaSteam(rs.getInt("app_id"), rs.getString("title"),
            rs.getInt("playtime_minutes"), rs.getString("icon_hash"), rs.getInt("unlocked_count"), rs.getInt("total_count"),
            rs.getString("cover_url")))
        .list();
  }

  Set<String> conquistasDesbloqueadas(String usuarioId, int appId) {
    return new HashSet<>(jdbc.sql("SELECT api_name FROM steam_user_achievements WHERE user_id = CAST(:usuarioId AS uuid) AND app_id = :appId")
        .param("usuarioId", usuarioId).param("appId", appId).query(String.class).list());
  }

  java.util.Map<String, Instant> conquistasDesbloqueadasComData(String usuarioId, int appId) {
    List<ConquistaComData> linhas = jdbc.sql(
        "SELECT api_name, unlocked_at FROM steam_user_achievements WHERE user_id = CAST(:usuarioId AS uuid) AND app_id = :appId")
        .param("usuarioId", usuarioId).param("appId", appId)
        .query((rs, linha) -> new ConquistaComData(
            rs.getString("api_name"),
            rs.getTimestamp("unlocked_at") == null ? null : rs.getTimestamp("unlocked_at").toInstant()))
        .list();
    java.util.Map<String, Instant> resultado = new java.util.LinkedHashMap<>();
    for (ConquistaComData linha : linhas) {
      resultado.put(linha.apiName(), linha.desbloqueadaEm());
    }
    return resultado;
  }

  private record ConquistaComData(String apiName, Instant desbloqueadaEm) {}

  void salvarConquistasDetalhadas(String usuarioId, int appId, List<ClienteSteamWeb.ConquistaSteam> conquistas) {
    for (ClienteSteamWeb.ConquistaSteam conquista : conquistas) {
      jdbc.sql("""
          INSERT INTO steam_user_achievements (user_id, app_id, api_name, title, unlocked_at)
          VALUES (CAST(:usuarioId AS uuid), :appId, :apiName, :titulo,
            CASE WHEN :desbloqueadaEm > 0 THEN to_timestamp(:desbloqueadaEm) ELSE NULL END)
          ON CONFLICT (user_id, app_id, api_name) DO UPDATE
            SET title = EXCLUDED.title, unlocked_at = COALESCE(steam_user_achievements.unlocked_at, EXCLUDED.unlocked_at)
          """)
          .param("usuarioId", usuarioId).param("appId", appId).param("apiName", conquista.identificador())
          .param("titulo", conquista.titulo()).param("desbloqueadaEm", conquista.desbloqueadaEm()).update();
    }
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
          COALESCE(SUM(a.total_count), 0) AS conquistas_total,
          COUNT(*) FILTER (WHERE a.total_count > 0 AND a.unlocked_count >= a.total_count) AS jogos_platinados
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
            rs.getLong("conquistas_total"),
            rs.getLong("jogos_platinados")))
        .single();
  }

  record ConexaoSteam(String steamId, String nome, String avatarUrl, String conectadoEm, String bibliotecaSincronizadaEm, String conquistasSincronizadasEm, String ultimoErro) {}
  record ConexaoUsuarioSteam(String usuarioId, String steamId) {}
  record JogoParaCapa(String usuarioId, int appId) {}
  record JogoBibliotecaSteam(int appId, String titulo, int minutosJogadas, String iconeHash, int conquistasDesbloqueadas, int conquistasTotal, String capaUrl) {
    JogoBibliotecaSteam(int appId, String titulo, int minutosJogadas, String iconeHash) {
      this(appId, titulo, minutosJogadas, iconeHash, 0, 0, null);
    }
  }
  // jogosPlatinados: jogos com 100% das conquistas desbloqueadas.
  record ResumoSteam(long totalJogos, long totalMinutos, long conquistasDesbloqueadas, long conquistasTotal, long jogosPlatinados) {}
}
