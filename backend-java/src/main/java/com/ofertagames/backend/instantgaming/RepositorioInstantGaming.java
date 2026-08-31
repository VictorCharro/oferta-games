package com.ofertagames.backend.instantgaming;

import com.ofertagames.backend.comum.ConteudosNaoJogos;
import com.ofertagames.backend.comum.JogosBloqueados;
import com.ofertagames.backend.comum.LojasBloqueadas;
import com.ofertagames.backend.comum.VariacaoPrecoRelevante;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistencia do modulo Instant Gaming: catalogo descoberto, cursor da varredura, casamento com
 * {@code games} e gravacao das ofertas.
 *
 * <p><b>Restricao de arquitetura:</b> nao depende do pacote {@code jogos}, pra evitar dependencia
 * circular — {@code ServicoCatalogo} (em {@code jogos}) chama {@code ServicoInstantGaming} no
 * refresh manual. Por isso escreve direto em {@code offers}/{@code games} com SQL propria,
 * duplicando de proposito o upsert de oferta e o registro de historico de preco que
 * {@code RepositorioJogos} ja faz para a fonte ITAD.
 *
 * <p>Consequencia pratica: mudanca na regra de gravacao de oferta ou de historico precisa ser
 * aplicada <b>nos dois lugares</b>.
 */
@Repository
class RepositorioInstantGaming {
  // Mesma prioridade dos outros jobs de coleta: top-2000 por rank primeiro, resto depois.
  private static final String PRIORIDADE_RANK =
      "CASE WHEN g.rank IS NOT NULL AND g.rank <= 2000 THEN 0 ELSE 1 END ASC, g.rank ASC NULLS LAST, ";

  private final JdbcClient jdbc;

  RepositorioInstantGaming(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  int buscarUltimoIdEscaneado() {
    return jdbc.sql("SELECT last_scanned_id FROM instant_gaming_scan_cursor WHERE id = true")
        .query(Integer.class)
        .single();
  }

  void avancarCursor(int novoValor) {
    jdbc.sql("UPDATE instant_gaming_scan_cursor SET last_scanned_id = :valor WHERE id = true")
        .param("valor", novoValor)
        .update();
  }

  void salvarNoCatalogo(int idProduto, String titulo, String tituloNormalizado, String url) {
    jdbc.sql("""
        INSERT INTO instant_gaming_catalog (product_id, title, normalized_title, url, discovered_at)
        VALUES (:id, :titulo, :tituloNormalizado, :url, now())
        ON CONFLICT (product_id) DO UPDATE
          SET title = EXCLUDED.title, normalized_title = EXCLUDED.normalized_title, url = EXCLUDED.url
        """)
        .param("id", idProduto)
        .param("titulo", titulo)
        .param("tituloNormalizado", tituloNormalizado)
        .param("url", url)
        .update();
  }

  /**
   * URL do produto cujo titulo normalizado casa com o informado — <b>e so quando o match e
   * unico</b>.
   *
   * <p>Ambiguidade (duas edicoes, remake com nome parecido) devolve vazio de proposito: linkar o
   * produto errado manda o usuario comprar outro jogo, o que e pior que ficar sem a oferta. Por
   * isso o {@code LIMIT 2} — basta saber se ha mais de um.
   *
   * @param tituloNormalizado precisa vir da mesma normalizacao de
   *     {@code GeradorSlug.porTitulo}, senao nunca casa
   */
  Optional<String> buscarUrlUnica(String tituloNormalizado) {
    List<String> urls = jdbc.sql("SELECT url FROM instant_gaming_catalog WHERE normalized_title = :titulo LIMIT 2")
        .param("titulo", tituloNormalizado)
        .query(String.class)
        .list();
    return urls.size() == 1 ? Optional.of(urls.get(0)) : Optional.empty();
  }

  long contarPendentesCasamento() {
    return jdbc.sql("""
        SELECT COUNT(*)
        FROM games g
        WHERE g.instant_gaming_url IS NULL
          %s
          %s
        """.formatted(ConteudosNaoJogos.filtroSql("g"), JogosBloqueados.filtroSql("g")))
        .query(Long.class)
        .single();
  }

  List<JogoParaCasar> listarPendentesCasamento(int limite) {
    return jdbc.sql("""
        SELECT g.id, g.title
        FROM games g
        WHERE g.instant_gaming_url IS NULL
          %s
          %s
        ORDER BY %s g.id ASC
        LIMIT :limite
        """.formatted(ConteudosNaoJogos.filtroSql("g"), JogosBloqueados.filtroSql("g"), PRIORIDADE_RANK))
        .param("limite", limite)
        .query((rs, linha) -> new JogoParaCasar(rs.getLong("id"), rs.getString("title")))
        .list();
  }

  long contarCatalogoDescoberto() {
    return jdbc.sql("SELECT COUNT(*) FROM instant_gaming_catalog").query(Long.class).single();
  }

  long contarCasados() {
    return jdbc.sql("""
        SELECT COUNT(*)
        FROM games g
        WHERE g.instant_gaming_url IS NOT NULL
          %s
          %s
        """.formatted(ConteudosNaoJogos.filtroSql("g"), JogosBloqueados.filtroSql("g")))
        .query(Long.class)
        .single();
  }

  void salvarMatch(long jogoId, String url) {
    jdbc.sql("UPDATE games SET instant_gaming_url = :url WHERE id = :id")
        .param("url", url)
        .param("id", jogoId)
        .update();
  }

  List<JogoParaAtualizarPreco> listarPendentesAtualizacaoPreco(int limite) {
    return jdbc.sql("""
        SELECT id, instant_gaming_url
        FROM games
        WHERE instant_gaming_url IS NOT NULL
        ORDER BY last_instant_gaming_sync_at ASC NULLS FIRST
        LIMIT :limite
        """)
        .param("limite", limite)
        .query((rs, linha) -> new JogoParaAtualizarPreco(rs.getLong("id"), rs.getString("instant_gaming_url")))
        .list();
  }

  void salvarPreco(long jogoId, BigDecimal preco, String moeda, String url) {
    jdbc.sql("""
        INSERT INTO offers (game_id, source, store_name, price, regular_price, currency, url, updated_at)
        VALUES (:jogoId, 'instant_gaming', 'Instant Gaming', :preco, NULL, :moeda, :url, now())
        ON CONFLICT (game_id, source, store_name) DO UPDATE
          SET price = EXCLUDED.price, currency = EXCLUDED.currency, url = EXCLUDED.url, updated_at = EXCLUDED.updated_at
        """)
        .param("jogoId", jogoId)
        .param("preco", preco)
        .param("moeda", moeda)
        .param("url", url)
        .update();
    jdbc.sql("UPDATE games SET last_instant_gaming_sync_at = now() WHERE id = :id")
        .param("id", jogoId)
        .update();
    registrarHistoricoDePreco(jogoId);
  }

  /**
   * Apaga a oferta Instant Gaming do jogo — usado quando o produto sai de estoque ou some.
   *
   * <p>Sem isso a oferta antiga ficaria parecendo disponivel pra sempre com o ultimo preco
   * conhecido, e como o menor preco do catalogo sai de {@code MIN(price)}, um produto indisponivel
   * poderia continuar sendo anunciado como a melhor oferta do jogo.
   */
  void removerOferta(long jogoId) {
    jdbc.sql("DELETE FROM offers WHERE game_id = :jogoId AND source = 'instant_gaming'")
        .param("jogoId", jogoId)
        .update();
    jdbc.sql("UPDATE games SET last_instant_gaming_sync_at = now() WHERE id = :id")
        .param("id", jogoId)
        .update();
    registrarHistoricoDePreco(jogoId);
  }

  /**
   * Grava um ponto de historico se o menor preco do jogo mudou.
   *
   * <p>Copia deliberada de {@code RepositorioJogos.registrarHistoricoDePrecos} — ver a nota de
   * dependencia circular no Javadoc da classe. <b>Alterar a regra de gravacao exige mudar os dois
   * lugares</b>, senao o historico passa a ter criterios diferentes conforme a fonte que atualizou
   * o preco.
   */
  private void registrarHistoricoDePreco(long jogoId) {
    jdbc.sql("""
        INSERT INTO price_history (game_id, price)
        SELECT atual.game_id, atual.preco
        FROM (
          SELECT o.game_id, MIN(o.price) AS preco
          FROM offers o
          WHERE o.game_id = :jogoId
            %s
          GROUP BY o.game_id
        ) atual
        LEFT JOIN LATERAL (
          SELECT ph.price
          FROM price_history ph
          WHERE ph.game_id = atual.game_id
          ORDER BY ph.captured_at DESC
          LIMIT 1
        ) ultimo ON true
        WHERE %s
        """.formatted(
            LojasBloqueadas.filtroSql("o"),
            VariacaoPrecoRelevante.condicaoSql("atual.preco", "ultimo.price")))
        .param("jogoId", jogoId)
        .update();
  }

  Optional<String> buscarInstantGamingUrl(long jogoId) {
    return jdbc.sql("SELECT instant_gaming_url FROM games WHERE id = :id")
        .param("id", jogoId)
        .query(String.class)
        .optional();
  }

  record JogoParaCasar(long id, String titulo) {}
  record JogoParaAtualizarPreco(long id, String url) {}
}
