package com.ofertagames.backend.jogos;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ofertagames.backend.comum.LimiteConsultasPesadas;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** SQL real em PostgreSQL descartavel local. Cada teste desfaz seus dados e DDL com rollback. */
@EnabledIfEnvironmentVariable(named = "TEST_CATALOGO_JDBC", matches = "jdbc:postgresql://127\\.0\\.0\\.1:.*")
class CatalogoPostgresTest {
  Connection conexao;
  JdbcTemplate template;
  NamedParameterJdbcTemplate nomeados;
  RepositorioJogos jogos;
  String consulta;
  Map<String, Object> parametros;

  @BeforeEach void abrir() throws Exception {
    conexao = DriverManager.getConnection(System.getenv("TEST_CATALOGO_JDBC"));
    conexao.setAutoCommit(false);
    var ds = new SingleConnectionDataSource(conexao, true);
    template = new JdbcTemplate(ds);
    nomeados = new NamedParameterJdbcTemplate(ds);
    var real = JdbcClient.create(ds);
    var cliente = mock(JdbcClient.class);
    when(cliente.sql(anyString())).thenAnswer(inv -> {
      consulta = inv.getArgument(0);
      parametros = new HashMap<>();
      var comando = spy(real.sql(consulta));
      doAnswer(p -> {
        parametros.put(p.getArgument(0), p.getArgument(1));
        p.callRealMethod();
        return comando;
      }).when(comando).param(anyString(), any());
      return comando;
    });
    jogos = new RepositorioJogos(cliente, template, new ObjectMapper(), new LimiteConsultasPesadas());
  }

  @AfterEach void fechar() throws Exception {
    if (conexao != null) { conexao.rollback(); conexao.close(); }
  }

  @Test void preservaAncoraDoHistoricoAposPoda() {
    template.update("INSERT INTO games(id,title,slug) VALUES (-99001,'Teste historico','teste-historico-revisao')");
    template.update("INSERT INTO price_history(game_id,price,captured_at) VALUES (-99001,10,now()-interval '120 days'),(-99001,20,now()-interval '100 days'),(-99001,30,now()-interval '10 days')");
    jogos.podarHistoricoDePrecos(90);
    assertEquals(2, template.queryForObject("SELECT count(*) FROM price_history WHERE game_id=-99001", Integer.class));
    var pontos = jogos.listarHistoricoDePrecos(-99001, 90);
    assertEquals(new BigDecimal("20.00"), pontos.getFirst().price());
    assertTrue(Duration.between(pontos.getFirst().capturadoEm(), Instant.now()).toDays() >= 89);
    assertEquals(new BigDecimal("30.00"), pontos.getLast().price());
  }

  @Test void expiraRankingSomenteQuandoAutorizadoPelaColetaCompleta() {
    String id = UUID.randomUUID().toString();
    template.update("INSERT INTO games(id, title, slug, rank, rank_updated_at) VALUES (-99001,'Velho','velho-revisao',1,now()-interval '8 days')");
    template.update("INSERT INTO games(id, itad_id, title, slug, rank) VALUES (-99002,?::uuid,'Atual','atual-revisao',2)", id);
    jogos.atualizarRanksPorItadId(Map.of(id, 2), false);
    assertEquals(1, template.queryForObject("SELECT rank FROM games WHERE id=-99001", Integer.class));
    jogos.atualizarRanksPorItadId(Map.of(id, 2), true);
    assertNull(template.queryForObject("SELECT rank FROM games WHERE id=-99001", Integer.class));
    assertEquals(2, template.queryForObject("SELECT rank FROM games WHERE id=-99002", Integer.class));
  }

  @Test void substituicaoVaziaMantemOutraFonteERegistraPrecoNovo() {
    template.update("INSERT INTO games(id,title,slug) VALUES (-99001,'Teste ofertas','teste-ofertas-revisao')");
    template.update("INSERT INTO offers(game_id,source,store_name,price,url,updated_at) VALUES (-99001,'itad','Steam',10,'https://example.com',now()),(-99001,'instant_gaming','Instant Gaming',20,'https://example.com',now())");
    jogos.registrarHistoricoDePrecos(List.of(-99001L));
    jogos.substituirOfertasItad(-99001L, List.of());
    assertEquals(new BigDecimal("20.00"), jogos.precosMinimos(List.of(-99001L)).get(-99001L));
    assertEquals(2, jogos.listarHistoricoDePrecos(-99001L, 90).size());
  }

  /**
   * O sitemap deixou de ser o catalogo inteiro em 22/09/2026: so jogo com oferta em loja visivel,
   * sem DLC, ordenado por rank. Este teste existe porque a mudanca e inteira em SQL — os testes de
   * {@code ServicoSitemapTest} usam o repositorio mockado e nao pegariam um filtro errado aqui.
   */
  @Test void sitemapTrazSoJogoComOfertaVisivelOrdenadoPorRank() {
    template.update("INSERT INTO games(id,title,slug,rank,is_dlc) VALUES "
        + "(-99001,'Popular','popular-revisao',5,false),"
        + "(-99002,'Sem rank','sem-rank-revisao',NULL,false),"
        + "(-99003,'Menos popular','menos-popular-revisao',900,false),"
        + "(-99004,'Sem oferta','sem-oferta-revisao',1,false),"
        + "(-99005,'Uma DLC','uma-dlc-revisao',2,true),"
        + "(-99006,'So loja bloqueada','so-bloqueada-revisao',3,false)");
    template.update("INSERT INTO offers(game_id,source,store_name,price,url,updated_at) VALUES "
        + "(-99001,'itad','Steam',10,'https://example.com',now()),"
        + "(-99002,'itad','Steam',10,'https://example.com',now()),"
        + "(-99003,'itad','Steam',10,'https://example.com',now()),"
        + "(-99005,'itad','Steam',10,'https://example.com',now()),"
        + "(-99006,'itad','GOG',10,'https://example.com',now())");

    var slugs = jogos.listarJogosParaSitemap(0, 500).stream()
        .map(RepositorioJogos.JogoParaSitemap::slug)
        .filter(s -> s.endsWith("-revisao"))
        .toList();

    assertEquals(List.of("popular-revisao", "menos-popular-revisao", "sem-rank-revisao"), slugs,
        "ordem por rank com os sem rank no fim; fora: sem oferta, DLC e so em loja bloqueada");
  }

  @Test void lastmodDoSitemapVemDoHistoricoENaoDaSincronizacao() {
    template.update("INSERT INTO games(id,title,slug,rank,is_dlc,created_at,last_price_sync_at) VALUES "
        + "(-99001,'Com historico','com-historico-revisao',1,false,now()-interval '90 days',now()),"
        + "(-99002,'Sem historico','sem-historico-revisao',2,false,now()-interval '40 days',now())");
    template.update("INSERT INTO offers(game_id,source,store_name,price,url,updated_at) VALUES "
        + "(-99001,'itad','Steam',10,'https://example.com',now()),"
        + "(-99002,'itad','Steam',10,'https://example.com',now())");
    template.update("INSERT INTO price_history(game_id,price,captured_at) VALUES "
        + "(-99001,20,now()-interval '30 days'),(-99001,10,now()-interval '3 days')");

    var porSlug = new HashMap<String, Instant>();
    for (var jogo : jogos.listarJogosParaSitemap(0, 500)) porSlug.put(jogo.slug(), jogo.atualizadoEm());

    // Com historico: a ULTIMA mudanca de preco (3 dias), nao o last_price_sync_at de agora.
    assertEquals(3, Duration.between(porSlug.get("com-historico-revisao"), Instant.now()).toDays());
    // Sem historico: cai no created_at, tambem nao no last_price_sync_at.
    assertEquals(40, Duration.between(porSlug.get("sem-historico-revisao"), Instant.now()).toDays());
  }

  @Test void medeBuscaComEsemIndiceTrigramaSemAlterarResultados() {
    var termos = List.of("portal", "gta", "re", "dark");
    Map<String, List<ResumoJogo>> antes = new LinkedHashMap<>();
    for (String termo : termos) antes.put(termo, medir(termo, "antes"));
    template.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
    template.execute("CREATE INDEX IF NOT EXISTS idx_games_title_trgm ON games USING gin(title gin_trgm_ops)");
    template.execute("ANALYZE games");
    for (String termo : termos) assertEquals(antes.get(termo), medir(termo, "depois"));
  }

  private List<ResumoJogo> medir(String termo, String etapa) {
    var resultado = jogos.listar(0,20,"rank","all","all",null,null,null,termo,List.of());
    var plano = nomeados.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + consulta, parametros, String.class);
    System.out.println("BUSCA " + etapa + " " + termo + ": " + String.join(" | ", plano.stream()
        .filter(l -> l.contains("Execution Time") || l.contains("idx_games_title_trgm")).toList()));
    return resultado;
  }
}
