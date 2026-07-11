package com.ofertagames.backend.jogos;

import com.ofertagames.backend.comum.ClassificadorDlc;
import com.ofertagames.backend.comum.ConteudosNaoJogos;
import com.ofertagames.backend.comum.LojasBloqueadas;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RepositorioJogos {
  private final JdbcClient jdbc;
  private final JdbcTemplate jdbcTemplate;

  RepositorioJogos(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
    this.jdbc = jdbc;
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<ResumoJogo> listar(int pagina, int tamanho, String ordenacao, String tipo, String plataforma, Double precoMinimo, Double precoMaximo, Double descontoMinimo, String busca) {
    int deslocamento = pagina * tamanho;
    String filtroTipo = switch (tipo) {
      case "dlc" -> "AND " + ClassificadorDlc.condicaoDlcSql("g");
      case "game" -> ClassificadorDlc.filtroApenasJogosSql("g");
      default -> "";
    };
    String filtroBusca = busca == null || busca.isBlank() ? "" : "AND g.title ILIKE :busca";
    String filtroPlataforma = filtroPlataforma(plataforma);
    String filtroOfertaPlataforma = filtroOfertaPlataforma(plataforma);
    String filtroLojaBloqueada = filtroLojaBloqueada("o");
    String filtroConteudoNaoJogo = ConteudosNaoJogos.filtroSql("g");
    String filtroPreco = filtroPreco(precoMinimo, precoMaximo, descontoMinimo);

    String sql = """
        SELECT
          g.slug,
          g.title,
          g.cover_url AS cover_url,
          g.is_dlc AS is_dlc,
          MIN(o.price) AS min_price,
          MAX(o.regular_price) AS regular_price,
          (ARRAY_AGG(o.store_name ORDER BY o.price ASC NULLS LAST) FILTER (WHERE o.store_name IS NOT NULL))[1] AS store_name,
          (ARRAY_AGG(o.url ORDER BY o.price ASC NULLS LAST) FILTER (WHERE o.url IS NOT NULL))[1] AS url
        FROM games g
        LEFT JOIN offers o ON o.game_id = g.id %s %s
        WHERE 1=1
        %s
        %s
        %s
        %s
        GROUP BY g.id, g.slug, g.title, g.cover_url, g.is_dlc
        %s
        ORDER BY %s
        LIMIT :tamanho OFFSET :deslocamento
        """.formatted(filtroOfertaPlataforma, filtroLojaBloqueada, filtroTipo, filtroBusca, filtroPlataforma, filtroConteudoNaoJogo, filtroPreco, ordenarPor(ordenacao));

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
    if (descontoMinimo != null) {
      comando = comando.param("descontoMinimo", descontoMinimo);
    }

    return comando.query(RepositorioJogos::mapearResumo).list();
  }

  public Optional<DetalheJogo> buscarPorSlug(String slug) {
    Optional<LinhaJogo> jogo = jdbc.sql("""
        SELECT id, slug, title, cover_url
        FROM games
        WHERE slug = :slug
          %s
        """.formatted(ConteudosNaoJogos.filtroSql("games")))
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
    return jdbc.sql("SELECT id FROM games WHERE slug = :slug " + ConteudosNaoJogos.filtroSql("games"))
        .param("slug", slug)
        .query(Long.class)
        .optional();
  }

  public long salvarJogoItad(String itadId, String titulo, String slug, String capa, Integer rank) {
    try {
      return jdbc.sql("""
          INSERT INTO games (itad_id, title, slug, cover_url, rank)
          VALUES (CAST(:itadId AS uuid), :titulo, :slug, :capa, :rank)
          ON CONFLICT (itad_id) DO UPDATE
            SET title = EXCLUDED.title,
                cover_url = COALESCE(EXCLUDED.cover_url, games.cover_url),
                rank = COALESCE(EXCLUDED.rank, games.rank)
          RETURNING id
          """)
          .param("itadId", itadId)
          .param("titulo", titulo)
          .param("slug", slug)
          .param("capa", capa)
          .param("rank", rank)
          .query(Long.class)
          .single();
    } catch (DuplicateKeyException conflito) {
      return buscarIdPorSlug(slug).orElseThrow(() -> conflito);
    }
  }

  public List<IdJogoItad> salvarJogosItad(List<JogoParaSalvar> jogos) {
    return jogos.stream()
        .filter(jogo -> !ConteudosNaoJogos.contem(jogo.title()))
        .map(jogo -> new IdJogoItad(
            salvarJogoItad(jogo.itadId(), jogo.title(), jogo.slug(), jogo.coverUrl(), jogo.rank()),
            jogo.itadId()))
        .toList();
  }

  public List<ResumoJogo> listarPorItadIds(List<String> itadIds) {
    return jdbc.sql("""
        SELECT
          g.slug,
          g.title,
          g.cover_url AS cover_url,
          g.is_dlc AS is_dlc,
          MIN(o.price) AS min_price,
          MAX(o.regular_price) AS regular_price,
          (ARRAY_AGG(o.store_name ORDER BY o.price ASC NULLS LAST) FILTER (WHERE o.store_name IS NOT NULL))[1] AS store_name,
          (ARRAY_AGG(o.url ORDER BY o.price ASC NULLS LAST) FILTER (WHERE o.url IS NOT NULL))[1] AS url
        FROM games g
        LEFT JOIN offers o ON o.game_id = g.id %s
        WHERE g.itad_id::text IN (:itadIds)
          %s
        GROUP BY g.id, g.slug, g.title, g.cover_url, g.is_dlc
        ORDER BY g.title
        """.formatted(filtroLojaBloqueada("o"), ConteudosNaoJogos.filtroSql("g")))
        .param("itadIds", itadIds)
        .query(RepositorioJogos::mapearResumo)
        .list();
  }

  public void salvarOferta(OfertaParaSalvar oferta) {
    if (lojaBloqueada(oferta.loja())) {
      return;
    }
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
    List<OfertaParaSalvar> ofertasPermitidas = ofertas.stream()
        .filter(oferta -> !lojaBloqueada(oferta.loja()))
        .toList();
    if (ofertasPermitidas.isEmpty()) {
      return 0;
    }

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
    int[][] updateCounts = jdbcTemplate.batchUpdate(sql, ofertasPermitidas, 100, (ps, o) -> {
      ps.setLong(1, o.jogoId());
      ps.setString(2, o.fonte());
      ps.setString(3, o.loja());
      ps.setBigDecimal(4, o.preco());
      ps.setBigDecimal(5, o.precoNormal());
      ps.setString(6, o.moeda());
      ps.setString(7, o.url());
    });
    return Arrays.stream(updateCounts)
        .flatMapToInt(Arrays::stream)
        .map(contagem -> contagem == Statement.SUCCESS_NO_INFO ? 1 : Math.max(contagem, 0))
        .sum();
  }

  @Transactional
  public int substituirOfertasItad(long jogoId, List<OfertaParaSalvar> ofertas) {
    jdbc.sql("DELETE FROM offers WHERE game_id = :jogoId AND source = 'itad'")
        .param("jogoId", jogoId)
        .update();
    return salvarOfertas(ofertas);
  }

  @Transactional
  public int substituirOfertasItadEmLote(Map<Long, List<OfertaParaSalvar>> ofertasPorJogo) {
    if (ofertasPorJogo.isEmpty()) {
      return 0;
    }
    List<Long> jogosIds = List.copyOf(ofertasPorJogo.keySet());
    jdbc.sql("DELETE FROM offers WHERE source = 'itad' AND game_id IN (:jogosIds)")
        .param("jogosIds", jogosIds)
        .update();
    List<OfertaParaSalvar> ofertas = ofertasPorJogo.values().stream()
        .flatMap(List::stream)
        .toList();
    return salvarOfertas(ofertas);
  }

  public List<JogoParaSincronizar> listarParaSincronizar(int limiteRelevantes, int limiteGerais) {
    return jdbc.sql("""
        WITH relevantes AS (
          SELECT id, itad_id::text AS itad_id
          FROM games
          WHERE itad_id IS NOT NULL AND rank IS NOT NULL AND rank <= 2000
            %s
          ORDER BY last_price_sync_at ASC NULLS FIRST, rank ASC, id ASC
          LIMIT :limiteRelevantes
        ), gerais AS (
          SELECT id, itad_id::text AS itad_id
          FROM games
          WHERE itad_id IS NOT NULL AND (rank IS NULL OR rank > 2000)
            %s
          ORDER BY last_price_sync_at ASC NULLS FIRST, rank ASC NULLS LAST, id ASC
          LIMIT :limiteGerais
        )
        SELECT id, itad_id FROM relevantes
        UNION ALL
        SELECT id, itad_id FROM gerais
        """.formatted(ConteudosNaoJogos.filtroSql("games"), ConteudosNaoJogos.filtroSql("games")))
        .param("limiteRelevantes", limiteRelevantes)
        .param("limiteGerais", limiteGerais)
        .query((rs, linha) -> new JogoParaSincronizar(rs.getLong("id"), rs.getString("itad_id")))
        .list();
  }

  public void marcarPrecosSincronizados(List<Long> jogosIds) {
    if (jogosIds.isEmpty()) {
      return;
    }
    jdbc.sql("UPDATE games SET last_price_sync_at = now() WHERE id IN (:jogosIds)")
        .param("jogosIds", jogosIds)
        .update();
  }

  public Optional<JogoParaAtualizar> buscarParaAtualizar(String slug) {
    return jdbc.sql("""
        SELECT id, title, itad_id::text AS itad_id, cover_url, is_dlc
        FROM games
        WHERE slug = :slug
          %s
        """.formatted(ConteudosNaoJogos.filtroSql("games")))
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
            cover_url = COALESCE(cover_url, :capaSteam),
            last_steam_sync_at = now()
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
        WHERE (g.is_dlc IS NULL OR g.cover_url IS NULL)
          %s
        ORDER BY g.last_steam_sync_at ASC NULLS FIRST, g.id ASC
        LIMIT :limite
        """.formatted(ConteudosNaoJogos.filtroSql("g")))
        .param("limite", limite)
        .query((rs, linha) -> new JogoSteamPendente(
            rs.getLong("id"),
            rs.getString("title"),
            rs.getString("url")))
        .list();
  }

  private List<OfertaJogo> listarOfertas(long jogoId) {
    return jdbc.sql("""
        SELECT o.store_name, o.price, o.regular_price, o.currency, o.url
        FROM offers o
        WHERE o.game_id = :jogoId
          %s
        ORDER BY price ASC
        """.formatted(filtroLojaBloqueada("o")))
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
        rs.getBigDecimal("regular_price"),
        rs.getString("store_name"),
        rs.getString("url"));
  }

  private static String filtroPlataforma(String plataforma) {
    String condicao = condicaoPlataforma("op", plataforma);
    if (condicao.isBlank()) return "";
    return "AND EXISTS (SELECT 1 FROM offers op WHERE op.game_id = g.id " + filtroLojaBloqueada("op") + " AND " + condicao + ")";
  }

  private static String filtroLojaBloqueada(String alias) {
    return LojasBloqueadas.filtroSql(alias);
  }

  private static boolean lojaBloqueada(String loja) {
    return LojasBloqueadas.contem(loja);
  }

  private static String filtroOfertaPlataforma(String plataforma) {
    String condicao = condicaoPlataforma("o", plataforma);
    return condicao.isBlank() ? "" : "AND " + condicao;
  }

  private static String condicaoPlataforma(String alias, String plataforma) {
    String origem = "lower(coalesce(" + alias + ".store_name, '') || ' ' || coalesce(" + alias + ".url, ''))";
    String condicao = switch (plataforma) {
      case "xbox" -> origem + " ~ 'xbox|microsoft'";
      case "playstation" -> origem + " ~ 'playstation|\\mpsn\\M|store\\.playstation\\.com'";
      case "pc" -> "(" + origem + " !~ 'xbox|playstation|\\mpsn\\M|store\\.playstation\\.com')";
      default -> "";
    };
    return condicao;
  }

  private static String filtroPreco(Double precoMinimo, Double precoMaximo, Double descontoMinimo) {
    List<String> condicoes = new java.util.ArrayList<>();
    if (precoMinimo != null) condicoes.add("MIN(o.price) >= :precoMinimo");
    if (precoMaximo != null) condicoes.add("MIN(o.price) <= :precoMaximo");
    if (descontoMinimo != null) {
      condicoes.add("ROUND((1 - MIN(o.price) / NULLIF(MAX(o.regular_price), 0)) * 100) >= :descontoMinimo");
    }
    return condicoes.isEmpty() ? "" : "HAVING " + String.join(" AND ", condicoes);
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

  private record LinhaJogo(Long id, String slug, String title, String coverUrl) {}
  record JogoParaSalvar(String itadId, String title, String slug, String coverUrl, Integer rank) {}
  record IdJogoItad(long id, String itadId) {}
  public record JogoParaSincronizar(long id, String itadId) {}
}
