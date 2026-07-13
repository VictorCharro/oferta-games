package com.ofertagames.backend.notificacoes;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RepositorioNotificacoes {
  private final JdbcClient jdbc;

  RepositorioNotificacoes(JdbcClient jdbc) { this.jdbc = jdbc; }

  public void registrarQueda(long jogoId, BigDecimal precoAnterior, BigDecimal precoAtual) {
    if (precoAnterior == null || precoAtual == null || precoAtual.compareTo(precoAnterior) >= 0) return;
    jdbc.sql("""
        INSERT INTO price_notifications (user_id, game_id, previous_price, current_price, store_name)
        SELECT f.user_id, f.game_id, :precoAnterior, :precoAtual,
          (SELECT o.store_name FROM offers o WHERE o.game_id = f.game_id ORDER BY o.price ASC, o.store_name ASC LIMIT 1)
        FROM favorites f
        WHERE f.game_id = :jogoId
        """)
        .param("jogoId", jogoId).param("precoAnterior", precoAnterior).param("precoAtual", precoAtual).update();
  }

  public List<NotificacaoPreco> listar(String usuarioId, int limite) {
    return jdbc.sql("""
        SELECT n.id, g.slug, g.title, n.previous_price, n.current_price, n.store_name,
               n.read_at IS NOT NULL AS lida, n.created_at::text AS criada_em
        FROM price_notifications n
        JOIN games g ON g.id = n.game_id
        WHERE n.user_id = CAST(:usuarioId AS uuid)
        ORDER BY n.created_at DESC
        LIMIT :limite
        """)
        .param("usuarioId", usuarioId).param("limite", limite)
        .query((rs, linha) -> new NotificacaoPreco(
            rs.getLong("id"), rs.getString("slug"), rs.getString("title"),
            rs.getBigDecimal("previous_price"), rs.getBigDecimal("current_price"), rs.getString("store_name"),
            rs.getBoolean("lida"), rs.getString("criada_em")))
        .list();
  }

  public long contarNaoLidas(String usuarioId) {
    return jdbc.sql("SELECT COUNT(*) FROM price_notifications WHERE user_id = CAST(:usuarioId AS uuid) AND read_at IS NULL")
        .param("usuarioId", usuarioId).query(Long.class).single();
  }

  public void marcarComoLida(String usuarioId, long id) {
    jdbc.sql("UPDATE price_notifications SET read_at = now() WHERE id = :id AND user_id = CAST(:usuarioId AS uuid)")
        .param("id", id).param("usuarioId", usuarioId).update();
  }

  public void marcarTodasComoLidas(String usuarioId) {
    jdbc.sql("UPDATE price_notifications SET read_at = now() WHERE user_id = CAST(:usuarioId AS uuid) AND read_at IS NULL")
        .param("usuarioId", usuarioId).update();
  }

  public void remover(String usuarioId, long id) {
    jdbc.sql("DELETE FROM price_notifications WHERE id = :id AND user_id = CAST(:usuarioId AS uuid)")
        .param("id", id).param("usuarioId", usuarioId).update();
  }
}
