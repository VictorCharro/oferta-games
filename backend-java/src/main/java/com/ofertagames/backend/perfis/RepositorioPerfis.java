package com.ofertagames.backend.perfis;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class RepositorioPerfis {
  private final JdbcClient jdbc;

  RepositorioPerfis(JdbcClient jdbc) { this.jdbc = jdbc; }

  Optional<Perfil> buscarPorUsuario(String usuarioId) {
    return jdbc.sql("SELECT user_id::text, handle, display_name, bio, avatar_url, is_public, show_game_hours, show_achievements, show_library, show_favorite_games FROM profiles WHERE user_id = CAST(:usuarioId AS uuid)")
        .param("usuarioId", usuarioId).query((rs, linha) -> mapear(rs)).optional();
  }

  Optional<Perfil> buscarPublicoPorHandle(String handle) {
    return jdbc.sql("SELECT user_id::text, handle, display_name, bio, avatar_url, is_public, show_game_hours, show_achievements, show_library, show_favorite_games FROM profiles WHERE handle = :handle AND is_public = true")
        .param("handle", handle).query((rs, linha) -> mapear(rs)).optional();
  }

  void salvar(String usuarioId, DadosPerfil dados) {
    jdbc.sql("""
        INSERT INTO profiles (user_id, handle, display_name, bio, avatar_url, is_public, show_game_hours, show_achievements, show_library, show_favorite_games, updated_at)
        VALUES (CAST(:usuarioId AS uuid), :handle, :nome, :bio, :avatar, :publico, :horas, :conquistas, :biblioteca, :favoritos, now())
        ON CONFLICT (user_id) DO UPDATE SET handle = EXCLUDED.handle, display_name = EXCLUDED.display_name, bio = EXCLUDED.bio,
          avatar_url = EXCLUDED.avatar_url, is_public = EXCLUDED.is_public, show_game_hours = EXCLUDED.show_game_hours,
          show_achievements = EXCLUDED.show_achievements, show_library = EXCLUDED.show_library,
          show_favorite_games = EXCLUDED.show_favorite_games, updated_at = now()
        """).param("usuarioId", usuarioId).param("handle", dados.handle()).param("nome", dados.nomeExibicao())
        .param("bio", dados.bio()).param("avatar", dados.avatarUrl()).param("publico", dados.publico())
        .param("horas", dados.mostrarHoras()).param("conquistas", dados.mostrarConquistas())
        .param("biblioteca", dados.mostrarBiblioteca()).param("favoritos", dados.mostrarFavoritos()).update();
  }

  private static Perfil mapear(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Perfil(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6), rs.getBoolean(7), rs.getBoolean(8), rs.getBoolean(9), rs.getBoolean(10));
  }

  record Perfil(String usuarioId, String handle, String nomeExibicao, String bio, String avatarUrl, boolean publico, boolean mostrarHoras, boolean mostrarConquistas, boolean mostrarBiblioteca, boolean mostrarFavoritos) {}
  record DadosPerfil(String handle, String nomeExibicao, String bio, String avatarUrl, boolean publico, boolean mostrarHoras, boolean mostrarConquistas, boolean mostrarBiblioteca, boolean mostrarFavoritos) {}
}
