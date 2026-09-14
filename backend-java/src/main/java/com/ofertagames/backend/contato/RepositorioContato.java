package com.ofertagames.backend.contato;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Mensagens do Fale conosco (tabela contact_messages, ver sql/20260914_mensagens_contato.sql). */
@Repository
class RepositorioContato {
  private final JdbcClient jdbc;

  RepositorioContato(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  void registrar(String tipo, String mensagem, String email, String usuarioId, String pagina) {
    jdbc.sql("""
        INSERT INTO contact_messages (kind, message, reply_email, user_id, page_path)
        VALUES (:tipo, :mensagem, :email, CAST(:usuarioId AS uuid), :pagina)
        """)
        .param("tipo", tipo)
        .param("mensagem", mensagem)
        .param("email", email)
        .param("usuarioId", usuarioId)
        .param("pagina", pagina)
        .update();
  }

  long contarDoUsuarioNasUltimas24h(String usuarioId) {
    return jdbc.sql("SELECT count(*) FROM contact_messages WHERE user_id = CAST(:id AS uuid) AND created_at > now() - interval '24 hours'")
        .param("id", usuarioId)
        .query(Long.class)
        .single();
  }

  List<MensagemAberta> listarAbertas() {
    return jdbc.sql("""
        SELECT m.id, m.kind, m.message, m.reply_email, m.page_path, m.created_at, p.handle
        FROM contact_messages m
        LEFT JOIN profiles p ON p.user_id = m.user_id
        WHERE m.resolved_at IS NULL
        ORDER BY m.created_at DESC
        LIMIT 200
        """)
        .query((rs, linha) -> new MensagemAberta(
            rs.getLong("id"),
            rs.getString("kind"),
            rs.getString("message"),
            rs.getString("reply_email"),
            rs.getString("page_path"),
            rs.getString("handle"),
            rs.getObject("created_at", OffsetDateTime.class)))
        .list();
  }

  int resolver(long id) {
    return jdbc.sql("UPDATE contact_messages SET resolved_at = now() WHERE id = :id AND resolved_at IS NULL")
        .param("id", id)
        .update();
  }

  record MensagemAberta(long id, String tipo, String mensagem, String email, String pagina, String handle,
      OffsetDateTime criadaEm) {}
}
