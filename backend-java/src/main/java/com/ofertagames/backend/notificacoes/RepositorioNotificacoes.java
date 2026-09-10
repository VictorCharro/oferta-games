package com.ofertagames.backend.notificacoes;

import com.ofertagames.backend.comum.LojasBloqueadas;
import com.ofertagames.backend.comum.VariacaoPrecoRelevante;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Alertas de preco dos Jogos Monitorados: criacao pela sincronizacao e leitura pelo sino da topbar.
 *
 * <p>{@code price_notifications}/{@code favorites} moram no Supabase (FK com {@code auth.users});
 * {@code games}/{@code offers} moram no Postgres do catalogo, na VM. {@link #registrarQueda}
 * resolve a loja mais barata no catalogo antes de gravar (era uma subquery em {@code offers}
 * dentro do INSERT, que so funcionava com os dois no mesmo Postgres); {@link #listar} busca nos
 * dois bancos e junta em Java.
 */
@Repository
public class RepositorioNotificacoes {
  private final JdbcClient jdbc;
  private final JdbcClient jdbcCatalogo;

  RepositorioNotificacoes(JdbcClient jdbc, @Qualifier("catalogo") JdbcClient jdbcCatalogo) {
    this.jdbc = jdbc;
    this.jdbcCatalogo = jdbcCatalogo;
  }

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
    String lojaMaisBarata = buscarLojaMaisBarata(jogoId);
    jdbc.sql("""
        INSERT INTO price_notifications (user_id, game_id, previous_price, current_price, store_name, tipo)
        SELECT f.user_id, f.game_id, :precoAnterior, :precoAtual, :lojaMaisBarata,
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
        """)
        .param("jogoId", jogoId)
        .param("precoAnterior", precoAnterior)
        .param("precoAtual", precoAtual)
        .param("quedaRelevante", quedaRelevante)
        .param("lojaMaisBarata", lojaMaisBarata)
        .update();
  }

  private String buscarLojaMaisBarata(long jogoId) {
    return jdbcCatalogo.sql("""
        SELECT o.store_name
        FROM offers o
        WHERE o.game_id = :jogoId
          %s
        ORDER BY o.price ASC, o.store_name ASC
        LIMIT 1
        """.formatted(LojasBloqueadas.filtroSql("o")))
        .param("jogoId", jogoId)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  public List<NotificacaoPreco> listar(String usuarioId, int limite) {
    List<NotificacaoBruta> notificacoes = jdbc.sql("""
        SELECT id, game_id, previous_price, current_price, store_name, tipo,
               read_at IS NOT NULL AS lida, created_at::text AS criada_em
        FROM price_notifications
        WHERE user_id = CAST(:usuarioId AS uuid)
        ORDER BY created_at DESC
        LIMIT :limite
        """)
        .param("usuarioId", usuarioId).param("limite", limite)
        .query((rs, linha) -> new NotificacaoBruta(
            rs.getLong("id"), rs.getLong("game_id"), rs.getBigDecimal("previous_price"),
            rs.getBigDecimal("current_price"), rs.getString("store_name"), rs.getString("tipo"),
            rs.getBoolean("lida"), rs.getString("criada_em")))
        .list();

    Map<Long, String[]> slugETituloPorJogo = buscarSlugETitulo(
        notificacoes.stream().map(NotificacaoBruta::gameId).distinct().toList());

    List<NotificacaoPreco> resultado = new ArrayList<>();
    for (NotificacaoBruta n : notificacoes) {
      String[] slugETitulo = slugETituloPorJogo.get(n.gameId());
      // Jogo que sumiu do catalogo nao aparece mais - mesmo comportamento do JOIN antigo.
      if (slugETitulo == null) continue;
      resultado.add(new NotificacaoPreco(
          n.id(), slugETitulo[0], slugETitulo[1], n.previousPrice(), n.currentPrice(), n.storeName(),
          n.tipo(), n.lida(), n.criadaEm()));
    }
    return resultado;
  }

  private Map<Long, String[]> buscarSlugETitulo(List<Long> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<Long, String[]> resultado = new HashMap<>();
    for (var linha : jdbcCatalogo.sql("SELECT id, slug, title FROM games WHERE id IN (:ids)")
        .param("ids", ids)
        .query((rs, l) -> Map.entry(rs.getLong("id"), new String[] {rs.getString("slug"), rs.getString("title")}))
        .list()) {
      resultado.put(linha.getKey(), linha.getValue());
    }
    return resultado;
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

  private record NotificacaoBruta(
      long id, long gameId, BigDecimal previousPrice, BigDecimal currentPrice, String storeName,
      String tipo, boolean lida, String criadaEm) {}
}
