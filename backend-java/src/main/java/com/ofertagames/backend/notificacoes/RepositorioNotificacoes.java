package com.ofertagames.backend.notificacoes;

import com.ofertagames.backend.comum.LojasBloqueadas;
import com.ofertagames.backend.comum.VariacaoPrecoRelevante;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Alertas de preco dos Jogos Monitorados: criacao pela sincronizacao e leitura pelo sino da topbar.
 */
@Repository
public class RepositorioNotificacoes {
  private final JdbcClient jdbc;

  RepositorioNotificacoes(JdbcClient jdbc) { this.jdbc = jdbc; }

  /**
   * Cria notificacoes de preco para quem monitora o jogo, quando o preco caiu.
   *
   * <p>Cada usuario recebe (ou nao) conforme a meta dele:
   *
   * <ul>
   *   <li><b>{@code meta_atingida}</b> — quem tem {@code favorites.target_price} e viu o preco
   *       cruzar essa meta agora (estava acima, ficou igual ou abaixo). Notifica
   *       <b>independente do tamanho da queda</b>: cruzar a meta e o evento que o usuario pediu,
   *       mesmo que a variacao seja de centavos.</li>
   *   <li><b>{@code queda}</b> — demais casos, so quando a queda passa do limiar de
   *       {@link VariacaoPrecoRelevante}. Sem isso, oscilacao de cambio virava alerta de "caiu de
   *       preco" a cada sincronizacao.</li>
   * </ul>
   *
   * <p>Quem ja estava com o preco abaixo da meta antes nao recebe {@code meta_atingida} de novo a
   * cada queda — so na travessia.
   *
   * @param precoAnterior menor preco antes de gravar as ofertas; {@code null} (jogo sem oferta
   *     antes) nao gera notificacao, pra estreia no catalogo nao virar "queda"
   */
  public void registrarQueda(long jogoId, BigDecimal precoAnterior, BigDecimal precoAtual) {
    if (precoAnterior == null || precoAtual == null || precoAtual.compareTo(precoAnterior) >= 0) return;

    boolean quedaRelevante = VariacaoPrecoRelevante.relevante(precoAnterior, precoAtual);
    jdbc.sql("""
        INSERT INTO price_notifications (user_id, game_id, previous_price, current_price, store_name, tipo)
        SELECT f.user_id, f.game_id, :precoAnterior, :precoAtual,
          (SELECT o.store_name FROM offers o
             WHERE o.game_id = f.game_id
               %s
             ORDER BY o.price ASC, o.store_name ASC LIMIT 1),
          CASE WHEN f.target_price IS NOT NULL
                    AND :precoAtual <= f.target_price
                    AND :precoAnterior > f.target_price
               THEN 'meta_atingida' ELSE 'queda' END
        FROM favorites f
        WHERE f.game_id = :jogoId
          AND (
            (f.target_price IS NOT NULL
               AND :precoAtual <= f.target_price
               AND :precoAnterior > f.target_price)
            OR :quedaRelevante
          )
        """.formatted(LojasBloqueadas.filtroSql("o")))
        .param("jogoId", jogoId)
        .param("precoAnterior", precoAnterior)
        .param("precoAtual", precoAtual)
        .param("quedaRelevante", quedaRelevante)
        .update();
  }

  public List<NotificacaoPreco> listar(String usuarioId, int limite) {
    return jdbc.sql("""
        SELECT n.id, g.slug, g.title, n.previous_price, n.current_price, n.store_name, n.tipo,
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
            rs.getString("tipo"),
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
