package com.ofertagames.backend.contato;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mensagens do Fale conosco (tabela contact_messages, ver sql/20260914_mensagens_contato.sql e
 * sql/20260914_resposta_mensagens_contato.sql).
 */
@Repository
class RepositorioContato {
  private final JdbcClient jdbc;

  RepositorioContato(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Grava a mensagem e os registros dos anexos (ja gravados no disco) de uma vez: ou entra tudo, ou
   * nada — sem mensagem apontando pra anexo que nao foi registrado.
   */
  @Transactional
  long registrar(String tipo, String mensagem, String email, String usuarioId, String pagina, List<AnexoGravado> anexos) {
    long id = jdbc.sql("""
        INSERT INTO contact_messages (kind, message, reply_email, user_id, page_path)
        VALUES (:tipo, :mensagem, :email, CAST(:usuarioId AS uuid), :pagina)
        RETURNING id
        """)
        .param("tipo", tipo)
        .param("mensagem", mensagem)
        .param("email", email)
        .param("usuarioId", usuarioId)
        .param("pagina", pagina)
        .query(Long.class)
        .single();
    for (AnexoGravado anexo : anexos) {
      jdbc.sql("""
          INSERT INTO contact_attachments (message_id, stored_name, content_type, size_bytes, original_name)
          VALUES (:id, :nome, :tipo, :tamanho, :original)
          """)
          .param("id", id)
          .param("nome", anexo.nomeNoDisco())
          .param("tipo", anexo.contentType())
          .param("tamanho", anexo.tamanho())
          .param("original", anexo.nomeOriginal())
          .update();
    }
    return id;
  }

  long totalBytesAnexos() {
    return jdbc.sql("SELECT coalesce(sum(size_bytes), 0) FROM contact_attachments").query(Long.class).single();
  }

  Optional<AnexoArmazenado> buscarAnexo(long id) {
    return jdbc.sql("SELECT stored_name, content_type, size_bytes, original_name FROM contact_attachments WHERE id = :id")
        .param("id", id)
        .query((rs, linha) -> new AnexoArmazenado(rs.getString("stored_name"), rs.getString("content_type"),
            rs.getLong("size_bytes"), rs.getString("original_name")))
        .optional();
  }

  private Map<Long, List<Anexo>> anexosPorMensagem(List<Long> ids) {
    if (ids.isEmpty()) return Map.of();
    Map<Long, List<Anexo>> resultado = new HashMap<>();
    jdbc.sql("""
        SELECT id, message_id, content_type, size_bytes, original_name
        FROM contact_attachments WHERE message_id IN (:ids) ORDER BY id
        """)
        .param("ids", ids)
        .query((rs, linha) -> {
          resultado.computeIfAbsent(rs.getLong("message_id"), k -> new ArrayList<>())
              .add(new Anexo(rs.getLong("id"), rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("original_name")));
          return null;
        })
        .list();
    return resultado;
  }

  long contarDoUsuarioNasUltimas24h(String usuarioId) {
    return jdbc.sql("SELECT count(*) FROM contact_messages WHERE user_id = CAST(:id AS uuid) AND created_at > now() - interval '24 hours'")
        .param("id", usuarioId)
        .query(Long.class)
        .single();
  }

  /** Abertas (todas, ate 200) ou lidas (as 100 mais recentes, pra consulta). */
  List<MensagemAdmin> listar(boolean lidas) {
    List<MensagemAdmin> mensagens = jdbc.sql("""
        SELECT m.id, m.kind, m.message, m.reply_email, m.page_path, m.created_at, p.handle,
               m.user_id IS NOT NULL AS respondivel, m.reply, m.replied_at
        FROM contact_messages m
        LEFT JOIN profiles p ON p.user_id = m.user_id
        WHERE (m.resolved_at IS NOT NULL) = :lidas
        ORDER BY m.created_at DESC
        LIMIT :limite
        """)
        .param("lidas", lidas)
        .param("limite", lidas ? 100 : 200)
        .query((rs, linha) -> new MensagemAdmin(
            rs.getLong("id"),
            rs.getString("kind"),
            rs.getString("message"),
            rs.getString("reply_email"),
            rs.getString("page_path"),
            rs.getString("handle"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getBoolean("respondivel"),
            rs.getString("reply"),
            rs.getObject("replied_at", OffsetDateTime.class),
            List.of()))
        .list();
    Map<Long, List<Anexo>> anexos = anexosPorMensagem(mensagens.stream().map(MensagemAdmin::id).toList());
    return mensagens.stream().map(m -> m.comAnexos(anexos.getOrDefault(m.id(), List.of()))).toList();
  }

  int resolver(long id) {
    return jdbc.sql("UPDATE contact_messages SET resolved_at = now() WHERE id = :id AND resolved_at IS NULL")
        .param("id", id)
        .update();
  }

  /**
   * Grava a resposta e ja tira a mensagem da fila. Responder de novo substitui a resposta e volta a
   * notificar. So vale pra mensagem com autor logado (user_id): retorna 0 pra anonima ou id inexistente.
   */
  int responder(long id, String resposta) {
    return jdbc.sql("""
        UPDATE contact_messages
        SET reply = :resposta, replied_at = now(), reply_read_at = NULL, resolved_at = coalesce(resolved_at, now())
        WHERE id = :id AND user_id IS NOT NULL
        """)
        .param("id", id)
        .param("resposta", resposta)
        .update();
  }

  /** Mensagens do proprio usuario, mais recentes primeiro, pra "Minhas mensagens" e o sino. */
  List<MinhaMensagem> listarDoUsuario(String usuarioId) {
    return jdbc.sql("""
        SELECT id, kind, message, created_at, reply, replied_at, reply_read_at IS NOT NULL AS resposta_lida
        FROM contact_messages
        WHERE user_id = CAST(:usuarioId AS uuid)
        ORDER BY coalesce(replied_at, created_at) DESC
        LIMIT 50
        """)
        .param("usuarioId", usuarioId)
        .query((rs, linha) -> new MinhaMensagem(
            rs.getLong("id"),
            rs.getString("kind"),
            rs.getString("message"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getString("reply"),
            rs.getObject("replied_at", OffsetDateTime.class),
            rs.getBoolean("resposta_lida")))
        .list();
  }

  void marcarRespostasLidas(String usuarioId) {
    jdbc.sql("""
        UPDATE contact_messages SET reply_read_at = now()
        WHERE user_id = CAST(:usuarioId AS uuid) AND replied_at IS NOT NULL AND reply_read_at IS NULL
        """)
        .param("usuarioId", usuarioId)
        .update();
  }

  record MensagemAdmin(long id, String tipo, String mensagem, String email, String pagina, String handle,
      OffsetDateTime criadaEm, boolean respondivel, String resposta, OffsetDateTime respondidaEm, List<Anexo> anexos) {
    MensagemAdmin comAnexos(List<Anexo> lista) {
      return new MensagemAdmin(id, tipo, mensagem, email, pagina, handle, criadaEm, respondivel, resposta, respondidaEm, lista);
    }
  }

  record Anexo(long id, String contentType, long tamanho, String nomeOriginal) {}
  record AnexoGravado(String nomeNoDisco, String contentType, long tamanho, String nomeOriginal) {}
  record AnexoArmazenado(String nomeNoDisco, String contentType, long tamanho, String nomeOriginal) {}

  record MinhaMensagem(long id, String tipo, String mensagem, OffsetDateTime criadaEm, String resposta,
      OffsetDateTime respondidaEm, boolean respostaLida) {}
}
