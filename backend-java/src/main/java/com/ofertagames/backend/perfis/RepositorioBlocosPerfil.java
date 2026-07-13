package com.ofertagames.backend.perfis;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class RepositorioBlocosPerfil {
  private final JdbcClient jdbc;

  RepositorioBlocosPerfil(JdbcClient jdbc) { this.jdbc = jdbc; }

  List<BlocoPerfil> listar(String usuarioId) {
    return jdbc.sql("SELECT block_id, block_type, title, content, position, size, visible, background_type, background_value, overlay_opacity, text_color FROM profile_blocks WHERE user_id = CAST(:usuarioId AS uuid) ORDER BY position")
        .param("usuarioId", usuarioId)
        .query((rs, linha) -> new BlocoPerfil(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getString(6), rs.getBoolean(7), rs.getString(8), rs.getString(9), rs.getInt(10), rs.getString(11))).list();
  }

  void substituir(String usuarioId, List<BlocoPerfil> blocos) {
    jdbc.sql("DELETE FROM profile_blocks WHERE user_id = CAST(:usuarioId AS uuid)").param("usuarioId", usuarioId).update();
    for (BlocoPerfil bloco : blocos) {
      jdbc.sql("INSERT INTO profile_blocks (user_id, block_id, block_type, title, content, position, size, visible, background_type, background_value, overlay_opacity, text_color) VALUES (CAST(:usuarioId AS uuid), :id, :tipo, :titulo, :conteudo, :posicao, :tamanho, :visivel, :fundoTipo, :fundoValor, :opacidade, :corTexto)")
          .param("usuarioId", usuarioId).param("id", bloco.id()).param("tipo", bloco.tipo()).param("titulo", bloco.titulo()).param("conteudo", bloco.conteudo()).param("posicao", bloco.posicao()).param("tamanho", bloco.tamanho()).param("visivel", true).param("fundoTipo", bloco.tipoFundo()).param("fundoValor", bloco.valorFundo()).param("opacidade", bloco.opacidade()).param("corTexto", bloco.corTexto()).update();
    }
  }

  record BlocoPerfil(String id, String tipo, String titulo, String conteudo, int posicao, String tamanho, boolean visivel, String tipoFundo, String valorFundo, int opacidade, String corTexto) {}
}
