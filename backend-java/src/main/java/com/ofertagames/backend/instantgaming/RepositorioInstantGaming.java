package com.ofertagames.backend.instantgaming;

import com.ofertagames.backend.comum.ConteudosNaoJogos;
import com.ofertagames.backend.comum.JogosBloqueados;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// Nao depende do pacote jogos (pra nao criar dependencia circular: ServicoCatalogo, em jogos,
// chama ServicoInstantGaming no refresh manual). Por isso grava direto em offers/games aqui,
// duplicando o upsert minimo que RepositorioJogos.salvarOferta ja faz pra fonte ITAD.
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

  // So retorna a URL quando o titulo normalizado casa com exatamente um produto: evita linkar
  // o jogo errado quando ha ambiguidade (edicoes/remakes com titulo parecido).
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
  }

  // Produto ficou fora de estoque (ou parou de existir): remove a oferta antiga, senao ela fica
  // parecendo disponivel pra sempre com o ultimo preco conhecido.
  void removerOferta(long jogoId) {
    jdbc.sql("DELETE FROM offers WHERE game_id = :jogoId AND source = 'instant_gaming'")
        .param("jogoId", jogoId)
        .update();
    jdbc.sql("UPDATE games SET last_instant_gaming_sync_at = now() WHERE id = :id")
        .param("id", jogoId)
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
