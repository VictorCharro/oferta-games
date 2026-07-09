package com.ofertagames.backend.jogos;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RepositorioJogos {
  private final JdbcClient jdbc;
  private final JdbcTemplate jdbcTemplate;

  RepositorioJogos(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
    this.jdbc = jdbc;
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<ResumoJogo> listar(int pagina, int tamanho, String ordenacao, String tipo, Double precoMinimo, Double precoMaximo, String busca) {
    int deslocamento = pagina * tamanho;
    String filtroTipo = switch (tipo) {
      case "dlc" -> "AND (g.is_dlc = true OR (g.is_dlc IS NULL AND " + tituloPareceDlcSql() + "))";
      case "game" -> "AND (g.is_dlc = false OR (g.is_dlc IS NULL AND NOT " + tituloPareceDlcSql() + "))";
      default -> "";
    };
    String filtroBusca = busca == null || busca.isBlank() ? "" : "AND g.title ILIKE :busca";
    String filtroPreco = filtroPreco(precoMinimo, precoMaximo);

    String sql = """
        SELECT
          g.slug,
          g.title,
          g.cover_url AS cover_url,
          g.is_dlc AS is_dlc,
          MIN(o.price) AS min_price,
          MAX(o.regular_price) AS regular_price
        FROM games g
        LEFT JOIN offers o ON o.game_id = g.id
        WHERE 1=1
        %s
        %s
        GROUP BY g.id, g.slug, g.title, g.cover_url, g.is_dlc
        %s
        ORDER BY %s
        LIMIT :tamanho OFFSET :deslocamento
        """.formatted(filtroTipo, filtroBusca, filtroPreco, ordenarPor(ordenacao));

    var comando = jdbc.sql(sql).param("tamanho", tamanho).param("deslocamento", deslocamento);
    if (busca != null && !busca.isBlank()) {
      comando = comando.param("busca", "%" + busca.trim() + "%");
    }
    if (precoMinimo != null) {
      comando = comando.param("precoMinimo", precoMinimo);
    }
    if (precoMaximo != null) {
      comando = comando.param("precoMaximo", precoMaximo);
    }

    return comando.query(RepositorioJogos::mapearResumo).list();
  }

  public Optional<DetalheJogo> buscarPorSlug(String slug) {
    Optional<LinhaJogo> jogo = jdbc.sql("""
        SELECT id, slug, title, cover_url
        FROM games
        WHERE slug = :slug
        """)
        .param("slug", slug)
        .query((rs, linha) -> new LinhaJogo(
            rs.getLong("id"),
            rs.getString("slug"),
            rs.getString("title"),
            rs.getString("cover_url")))
        .optional();

    return jogo.map(linha -> new DetalheJogo(linha.id(), linha.slug(), linha.title(), linha.coverUrl(), listarOfertas(linha.id())));
  }

  public Optional<Long> buscarIdPorSlug(String slug) {
    return jdbc.sql("SELECT id FROM games WHERE slug = :slug")
        .param("slug", slug)
        .query(Long.class)
        .optional();
  }

  public List<IdJogoItad> salvarJogosItad(List<JogoParaSalvar> jogos) {
    String sql = """
        INSERT INTO games (itad_id, title, slug, cover_url, rank)
        SELECT itad_id, title, slug, cover_url, rank
        FROM UNNEST(?)
        ON CONFLICT (itad_id) DO UPDATE
          SET title = EXCLUDED.title,
              cover_url = COALESCE(EXCLUDED.cover_url, games.cover_url),
              rank = COALESCE(EXCLUDED.rank, games.rank)
        RETURNING id, itad_id::text
        """;
    return jdbc.sql(sql)
        .param(1, jogos.toArray(new JogoParaSalvar[0]))
        .query((rs, linha) -> new IdJogoItad(rs.getLong("id"), rs.getString("itad_id")))
        .list();
  }

  public List<ResumoJogo> listarPorItadIds(List<String> itadIds) {
    return jdbc.sql("""
        SELECT
          g.slug,
          g.title,
          g.cover_url AS cover_url,
          g.is_dlc AS is_dlc,
          MIN(o.price) AS min_price,
          MAX(o.regular_price) AS regular_price
        FROM games g
        LEFT JOIN offers o ON o.game_id = g.id
        WHERE g.itad_id::text IN (:itadIds)
        GROUP BY g.id, g.slug, g.title, g.cover_url, g.is_dlc
        ORDER BY g.title
        """)
        .param("itadIds", itadIds)
        .query(RepositorioJogos::mapearResumo)
        .list();
  }

  public void salvarOferta(OfertaParaSalvar oferta) {
    jdbc.sql("""
        INSERT INTO offers (game_id, source, store_name, price, regular_price, currency, url, updated_at)
        VALUES (:jogoId, :fonte, :loja, :preco, :precoNormal, :moeda, :url, now())
        ON CONFLICT (game_id, source, store_name) DO UPDATE
          SET price = EXCLUDED.price,
              regular_price = EXCLUDED.regular_price,
              currency = EXCLUDED.currency,
              url = EXCLUDED.url,
              updated_at = EXCLUDED.updated_at
        """)
        .param("jogoId", oferta.jogoId())
        .param("fonte", oferta.fonte())
        .param("loja", oferta.loja())
        .param("preco", oferta.preco())
        .param("precoNormal", oferta.precoNormal())
        .param("moeda", oferta.moeda())
        .param("url", oferta.url())
        .update();
  }

  public int salvarOfertas(List<OfertaParaSalvar> ofertas) {
    String sql = """
        INSERT INTO offers (game_id, source, store_name, price, regular_price, currency, url, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, now())
        ON CONFLICT (game_id, source, store_name) DO UPDATE
          SET price = EXCLUDED.price,
              regular_price = EXCLUDED.regular_price,
              currency = EXCLUDED.currency,
              url = EXCLUDED.url,
              updated_at = EXCLUDED.updated_at
        """;
    int[][] updateCounts = jdbcTemplate.batchUpdate(sql, ofertas, 100, (ps, o) -> {
      ps.setLong(1, o.jogoId());
      ps.setString(2, o.fonte());
      ps.setString(3, o.loja());
      ps.setBigDecimal(4, o.preco());
      ps.setBigDecimal(5, o.precoNormal());
      ps.setString(6, o.moeda());
      ps.setString(7, o.url());
    });
    return Arrays.stream(updateCounts).flatMapToInt(Arrays::stream).sum();
  }

  public Optional<JogoParaAtualizar> buscarParaAtualizar(String slug) {
    return jdbc.sql("""
        SELECT id, title, itad_id::text AS itad_id, cover_url, is_dlc
        FROM games
        WHERE slug = :slug
        """)
        .param("slug", slug)
        .query((rs, linha) -> new JogoParaAtualizar(
            rs.getLong("id"),
            rs.getString("title"),
            rs.getString("itad_id"),
            rs.getString("cover_url"),
            rs.getObject("is_dlc", Boolean.class)))
        .optional();
  }

  public void atualizarMetadadosSteam(long jogoId, Boolean ehDlc, String capaSteam) {
    jdbc.sql("""
        UPDATE games
        SET is_dlc = COALESCE(:ehDlc, is_dlc),
            cover_url = COALESCE(cover_url, :capaSteam)
        WHERE id = :jogoId
        """)
        .param("ehDlc", ehDlc)
        .param("capaSteam", capaSteam)
        .param("jogoId", jogoId)
        .update();
  }

  public List<JogoSteamPendente> listarPendentesSteam(int limite) {
    return jdbc.sql("""
        SELECT g.id, g.title, o.url
        FROM games g
        JOIN offers o ON o.game_id = g.id AND o.store_name = 'Steam'
        WHERE g.is_dlc IS NULL
        ORDER BY g.id
        LIMIT :limite
        """)
        .param("limite", limite)
        .query((rs, linha) -> new JogoSteamPendente(
            rs.getLong("id"),
            rs.getString("title"),
            rs.getString("url")))
        .list();
  }

  private List<OfertaJogo> listarOfertas(long jogoId) {
    return jdbc.sql("""
        SELECT store_name, price, regular_price, currency, url
        FROM offers
        WHERE game_id = :jogoId
        ORDER BY price ASC
        """)
        .param("jogoId", jogoId)
        .query((rs, linha) -> new OfertaJogo(
            rs.getString("store_name"),
            rs.getBigDecimal("price"),
            rs.getBigDecimal("regular_price"),
            rs.getString("currency"),
            rs.getString("url")))
        .list();
  }

  private static ResumoJogo mapearResumo(ResultSet rs, int linha) throws SQLException {
    return new ResumoJogo(
        rs.getString("slug"),
        rs.getString("title"),
        rs.getString("cover_url"),
        rs.getObject("is_dlc", Boolean.class),
        rs.getBigDecimal("min_price"),
        rs.getBigDecimal("regular_price"));
  }

  private static String filtroPreco(Double precoMinimo, Double precoMaximo) {
    if (precoMinimo != null && precoMaximo != null) {
      return "HAVING MIN(o.price) >= :precoMinimo AND MIN(o.price) <= :precoMaximo";
    }
    if (precoMinimo != null) {
      return "HAVING MIN(o.price) >= :precoMinimo";
    }
    if (precoMaximo != null) {
      return "HAVING MIN(o.price) <= :precoMaximo";
    }
    return "";
  }

  private static String ordenarPor(String ordenacao) {
    return switch (ordenacao) {
      case "discount" -> "ROUND((1 - MIN(o.price) / NULLIF(MAX(o.regular_price), 0)) * 100) DESC NULLS LAST, g.rank ASC NULLS LAST";
      case "price_asc" -> "MIN(o.price) ASC NULLS LAST";
      case "price_desc" -> "MIN(o.price) DESC NULLS LAST";
      default -> """
          CASE
            WHEN g.rank <= 200
              AND MIN(o.price) IS NOT NULL
              AND MAX(o.regular_price) IS NOT NULL
              AND MIN(o.price) < MAX(o.regular_price) * 0.99
            THEN 0 ELSE 1
          END ASC,
          CASE
            WHEN g.rank <= 200
              AND MIN(o.price) IS NOT NULL
              AND MAX(o.regular_price) IS NOT NULL
              AND MIN(o.price) < MAX(o.regular_price) * 0.99
            THEN ROUND((1 - MIN(o.price) / NULLIF(MAX(o.regular_price), 0)) * 100)
            ELSE NULL
          END DESC NULLS LAST,
          g.rank ASC NULLS LAST,
          g.id ASC
          """;
    };
  }

  private static String tituloPareceDlcSql() {
    return """
        (g.title ILIKE '%DLC%'
          OR g.title ILIKE '%Season Pass%'
          OR g.title ILIKE '%Soundtrack%'
          OR g.title ILIKE '% OST%'
          OR g.title ILIKE '%Art Book%'
          OR g.title ILIKE '%Skin Set%'
          OR g.title ILIKE '%Skin Pack%'
          OR g.title ILIKE '%Booster Pack%'
          OR g.title ILIKE '%Expansion%'
          OR g.title ILIKE '%Add-on%')
        """;
  }

  private record LinhaJogo(Long id, String slug, String title, String coverUrl) {}
  record JogoParaSalvar(String itadId, String title, String slug, String coverUrl, Integer rank) {}
  record IdJogoItad(long id, String itadId) {}
}