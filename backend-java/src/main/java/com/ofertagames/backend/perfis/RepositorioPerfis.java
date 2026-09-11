package com.ofertagames.backend.perfis;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class RepositorioPerfis {
  private final JdbcClient jdbc;

  RepositorioPerfis(JdbcClient jdbc) { this.jdbc = jdbc; }

  private static final String COLUNAS = "user_id::text, handle, display_name, bio, avatar_url, is_public, show_game_hours, show_achievements, show_library, show_favorite_games, show_recent_activity, avatar_zoom, avatar_position_x, avatar_position_y, banner_url, banner_zoom, banner_position_x, banner_position_y, show_collections, show_steam_wishlist";

  Optional<Perfil> buscarPorUsuario(String usuarioId) {
    return jdbc.sql("SELECT " + COLUNAS + " FROM profiles WHERE user_id = CAST(:usuarioId AS uuid)")
        .param("usuarioId", usuarioId).query((rs, linha) -> mapear(rs)).optional();
  }

  Optional<Perfil> buscarPublicoPorHandle(String handle) {
    return jdbc.sql("SELECT " + COLUNAS + " FROM profiles WHERE handle = :handle AND is_public = true")
        .param("handle", handle).query((rs, linha) -> mapear(rs)).optional();
  }

  Optional<Perfil> buscarPorHandle(String handle) {
    return jdbc.sql("SELECT " + COLUNAS + " FROM profiles WHERE handle = :handle")
        .param("handle", handle).query((rs, linha) -> mapear(rs)).optional();
  }

  void salvar(String usuarioId, DadosPerfil dados) {
    jdbc.sql("""
        INSERT INTO profiles (user_id, handle, display_name, bio, avatar_url, is_public, show_game_hours, show_achievements, show_library, show_favorite_games, show_recent_activity, show_collections, updated_at)
        VALUES (CAST(:usuarioId AS uuid), :handle, :nome, :bio, :avatar, :publico, :horas, :conquistas, :biblioteca, :favoritos, :atividades, :colecoes, now())
        ON CONFLICT (user_id) DO UPDATE SET handle = EXCLUDED.handle, display_name = EXCLUDED.display_name, bio = EXCLUDED.bio,
          avatar_url = EXCLUDED.avatar_url, is_public = EXCLUDED.is_public, show_game_hours = EXCLUDED.show_game_hours,
          show_achievements = EXCLUDED.show_achievements, show_library = EXCLUDED.show_library,
          show_favorite_games = EXCLUDED.show_favorite_games, show_recent_activity = EXCLUDED.show_recent_activity,
          show_collections = EXCLUDED.show_collections, updated_at = now()
        """).param("usuarioId", usuarioId).param("handle", dados.handle()).param("nome", dados.nomeExibicao())
        .param("bio", dados.bio()).param("avatar", dados.avatarUrl()).param("publico", dados.publico())
        .param("horas", dados.mostrarHoras()).param("conquistas", dados.mostrarConquistas())
        .param("biblioteca", dados.mostrarBiblioteca()).param("favoritos", dados.mostrarFavoritos())
        .param("atividades", dados.mostrarAtividades()).param("colecoes", dados.mostrarColecoes()).update();
  }

  void atualizarAvatar(String usuarioId, String avatarUrl, double zoom, int posicaoX, int posicaoY) {
    jdbc.sql("UPDATE profiles SET avatar_url = :avatar, avatar_zoom = :zoom, avatar_position_x = :posicaoX, avatar_position_y = :posicaoY, updated_at = now() WHERE user_id = CAST(:usuarioId AS uuid)")
        .param("usuarioId", usuarioId).param("avatar", avatarUrl).param("zoom", zoom).param("posicaoX", posicaoX).param("posicaoY", posicaoY).update();
  }

  void atualizarBanner(String usuarioId, String bannerUrl, double zoom, int posicaoX, int posicaoY) {
    jdbc.sql("UPDATE profiles SET banner_url = :banner, banner_zoom = :zoom, banner_position_x = :posicaoX, banner_position_y = :posicaoY, updated_at = now() WHERE user_id = CAST(:usuarioId AS uuid)")
        .param("usuarioId", usuarioId).param("banner", bannerUrl).param("zoom", zoom).param("posicaoX", posicaoX).param("posicaoY", posicaoY).update();
  }

  // Toggle isolado (nao entra no salvar() geral): fica dentro do modo Organizar da aba Colecoes,
  // ao lado da wishlist da Steam, nao em Configuracoes > Privacidade como os outros mostrar*.
  void atualizarMostrarWishlistSteam(String usuarioId, boolean mostrar) {
    jdbc.sql("UPDATE profiles SET show_steam_wishlist = :mostrar, updated_at = now() WHERE user_id = CAST(:usuarioId AS uuid)")
        .param("usuarioId", usuarioId).param("mostrar", mostrar).update();
  }

  private static Perfil mapear(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Perfil(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6), rs.getBoolean(7), rs.getBoolean(8), rs.getBoolean(9), rs.getBoolean(10), rs.getBoolean(11), rs.getDouble(12), rs.getInt(13), rs.getInt(14), rs.getString(15), rs.getDouble(16), rs.getInt(17), rs.getInt(18), rs.getBoolean(19), rs.getBoolean(20));
  }

  record Perfil(String usuarioId, String handle, String nomeExibicao, String bio, String avatarUrl, boolean publico, boolean mostrarHoras, boolean mostrarConquistas, boolean mostrarBiblioteca, boolean mostrarFavoritos, boolean mostrarAtividades, double avatarZoom, int avatarPosicaoX, int avatarPosicaoY, String bannerUrl, double bannerZoom, int bannerPosicaoX, int bannerPosicaoY, boolean mostrarColecoes, boolean mostrarWishlistSteam) {}
  record DadosPerfil(String handle, String nomeExibicao, String bio, String avatarUrl, boolean publico, boolean mostrarHoras, boolean mostrarConquistas, boolean mostrarBiblioteca, boolean mostrarFavoritos, boolean mostrarAtividades, boolean mostrarColecoes) {}
}
