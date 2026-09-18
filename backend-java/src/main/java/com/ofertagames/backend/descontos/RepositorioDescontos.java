package com.ofertagames.backend.descontos;

import com.ofertagames.backend.comum.ClassificadorDlc;
import com.ofertagames.backend.comum.ConfiguracaoCache;
import com.ofertagames.backend.comum.ConteudosNaoJogos;
import com.ofertagames.backend.comum.JogosBloqueados;
import com.ofertagames.backend.comum.LimiteConsultasPesadas;
import com.ofertagames.backend.comum.LojasBloqueadas;
import com.ofertagames.backend.comum.ParametrosPublicos;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Ranking de melhores descontos — alimenta os carrosseis da Home e a pagina Gratuitos.
 */
@Repository
public class RepositorioDescontos {
  private final JdbcClient jdbc;
  private final LimiteConsultasPesadas limite;

  RepositorioDescontos(@Qualifier("catalogo") JdbcClient jdbc, LimiteConsultasPesadas limite) {
    this.jdbc = jdbc;
    this.limite = limite;
  }

  /**
   * Melhores descontos do catalogo, ja com o preco e a loja que serao exibidos.
   *
   * <p><b>Elegibilidade e preco vem de ofertas diferentes</b>, e isso e intencional:
   *
   * <ul>
   *   <li>o {@code discount_pct} e o fato de o jogo entrar na lista saem da <b>melhor oferta com
   *       desconto real</b> ({@code DISTINCT ON});</li>
   *   <li>o preco e a loja exibidos saem da <b>oferta mais barata entre todas</b>
   *       ({@code JOIN LATERAL}), mesmo que ela nao esteja em promocao.</li>
   * </ul>
   *
   * <p>Sem essa separacao a Home anunciava um preco maior que o "melhor preco" da propria pagina do
   * jogo, quando a loja mais barata nao era a que estava com desconto oficial.
   *
   * <p>Regras que valem a pena conhecer:
   *
   * <ul>
   *   <li><b>oferta a preco 0 so conta se o jogo for pago em alguma loja</b> (desconto de 100% com
   *       {@code regular_price}, como o gratis semanal da Epic, ou de graca numa loja e vendido em
   *       outra). E o que separa "de graca agora" de "sempre gratuito" (PUBG, Dota 2, Warframe),
   *       que nao tem preco em lugar nenhum. Ate 16/09/2026 qualquer {@code price = 0} valia como
   *       100% off e a pagina /gratuitos — que e so este topo filtrado por 100% — encheu de
   *       free-to-play assim que a descoberta de jogos novos importou os grandes F2P da Steam.
   *       Free-to-play continua no catalogo e na busca, so nao conta como promocao;</li>
   *   <li>desconto so conta a partir de 1% ({@code price < regular_price * 0.99}), pra ruido de
   *       conversao cambial nao virar "promocao";</li>
   *   <li>{@code tipo = "dlc"} filtra DLC <b>na query</b>. Filtrar depois, no frontend, deixava o
   *       carrossel de DLCs quase vazio: como DLC e fatia pequena do catalogo, os jogos base
   *       dominam o topo do ranking geral.</li>
   * </ul>
   *
   * <p><b>Sempre devolve o topo inteiro</b> ({@link ParametrosPublicos#TAMANHO_TOPO_DESCONTOS}), e
   * quem chama fatia. O custo da query quase nao depende do tamanho — o {@code DISTINCT ON} passa
   * pelas ofertas de todo o catalogo de qualquer jogo —, entao ter o tamanho na chave do cache so
   * multiplicava as chaves (200 tamanhos x ordenacoes x tipos), cada uma pagando a query inteira.
   * Assim existem no maximo 6 entradas (issue #16).
   *
   * <p>{@code sync = true}: requisicoes simultaneas pela mesma chave esperam UMA execucao, em vez
   * de cada SSR que chega com o cache frio (deploy, expiracao) disparar a sua.
   *
   * @param ordenacao {@code rank} ou {@code discount}, ja normalizada por
   *     {@link ParametrosPublicos#ordenacaoDescontos}
   * @param tipo {@code all}, {@code game} ou {@code dlc}, ja normalizado
   */
  @Cacheable(value = ConfiguracaoCache.CACHE_DESCONTOS, sync = true)
  public List<DescontoJogo> listarTopo(String ordenacao, String tipo) {
    return limite.executar("descontos " + ordenacao + "/" + tipo, () -> consultarTopo(ordenacao, tipo));
  }

  private List<DescontoJogo> consultarTopo(String ordenacao, String tipo) {
    String ordenarPor = "rank".equals(ordenacao)
        ? "rank ASC NULLS LAST, discount_pct DESC"
        : "discount_pct DESC, rank ASC NULLS LAST";
    String filtroTipo = switch (tipo) {
      case "dlc" -> "AND " + ClassificadorDlc.condicaoDlcSql("g");
      case "game" -> ClassificadorDlc.filtroApenasJogosSql("g");
      default -> "";
    };

    // A elegibilidade (jogo entra na lista) e o discount_pct exibido vem da MELHOR oferta com
    // desconto real. Mas o preco/loja exibidos vem da oferta mais barata entre TODAS (LATERAL),
    // pra bater com o "melhor preco" mostrado na pagina do jogo — mesmo quando essa loja mais
    // barata nao tem desconto oficial (ex: preco padrao mais baixo em outra loja).
    // LIMIT ANTES do LATERAL: a ordenacao final so usa discount_pct e rank (que ja vem de
    // "elegiveis"), entao da pra cortar o topo primeiro e buscar a oferta mais barata so pra essas
    // linhas. Antes o LATERAL rodava pros ~31 mil jogos elegiveis e o LIMIT descartava quase tudo
    // (126 mil buffers pra devolver 200). O resultado e o mesmo: a oferta que tornou o jogo
    // elegivel ja passou pelo filtro de loja bloqueada, entao o LATERAL sempre acha ao menos ela.
    String sql = """
        SELECT * FROM (
          SELECT * FROM (
          SELECT DISTINCT ON (g.id)
            g.id AS game_id,
            g.slug,
            g.title,
            g.cover_url,
            g.is_dlc,
            g.rank,
            CASE WHEN o.price = 0 THEN 100 ELSE ROUND((1 - o.price / o.regular_price) * 100)::integer END AS discount_pct
          FROM offers o
          JOIN games g ON g.id = o.game_id
          WHERE (
              (o.regular_price IS NOT NULL AND o.regular_price > 0 AND o.price < o.regular_price * 0.99)
              OR (o.price = 0 AND EXISTS (
                    SELECT 1 FROM offers pago
                    WHERE pago.game_id = g.id AND pago.price > 0
                      %s
                  ))
            )
            %s
            %s
            %s
            %s
          ORDER BY g.id, (CASE WHEN o.price = 0 THEN 100 ELSE ROUND((1 - o.price / o.regular_price) * 100)::integer END) DESC
          ) todos_elegiveis
          ORDER BY %s
          LIMIT :tamanho
        ) elegiveis
        JOIN LATERAL (
          SELECT o.store_name, o.price, o.regular_price, o.url
          FROM offers o
          WHERE o.game_id = elegiveis.game_id
            %s
          ORDER BY o.price ASC
          LIMIT 1
        ) barato ON true
        ORDER BY %s
        """.formatted(
            LojasBloqueadas.filtroSql("pago"),
            LojasBloqueadas.filtroSql("o"),
            ConteudosNaoJogos.filtroSql("g"),
            JogosBloqueados.filtroSql("g"),
            filtroTipo,
            ordenarPor,
            LojasBloqueadas.filtroSql("o"),
            ordenarPor);

    return jdbc.sql(sql)
        .param("tamanho", ParametrosPublicos.TAMANHO_TOPO_DESCONTOS)
        .query((rs, linha) -> new DescontoJogo(
            rs.getString("slug"),
            rs.getString("title"),
            rs.getString("cover_url"),
            rs.getObject("is_dlc", Boolean.class),
            rs.getObject("rank", Integer.class),
            rs.getString("store_name"),
            rs.getBigDecimal("price"),
            rs.getBigDecimal("regular_price"),
            rs.getString("url"),
            rs.getObject("discount_pct", Integer.class)))
        .list();
  }

  /**
   * Lancamentos em alta pra Home (18/09/2026): jogos populares lancados nos ultimos 60 dias ou com
   * lancamento previsto nos proximos 180, com o menor preco atual — tenham desconto ou nao.
   *
   * <p>Existe porque lancamento quase nunca esta em promocao, entao nunca aparecia nas secoes de
   * desconto da Home, por mais popular que fosse. "Popular" = rank ate 3.000 (ver
   * ServicoRankingJogos); jogo sem rank fica de fora pra lista nao encher de lancamento obscuro.
   *
   * <p>{@code game_details.release_date} e texto da Steam em pt-BR ("24/set./2026"). O formato e
   * interpretado aqui; o que nao bate ("Em breve", "2027", "4º trimestre") fica de fora.
   */
  @Cacheable(value = ConfiguracaoCache.CACHE_DESCONTOS, key = "'lancamentos'", sync = true)
  public List<DescontoJogo> listarLancamentos() {
    String sql = """
        WITH datas AS (
          SELECT gd.game_id,
                 make_date(
                   split_part(gd.release_date, '/', 3)::int,
                   CASE split_part(gd.release_date, '/', 2)
                     WHEN 'jan.' THEN 1 WHEN 'fev.' THEN 2 WHEN 'mar.' THEN 3 WHEN 'abr.' THEN 4
                     WHEN 'mai.' THEN 5 WHEN 'jun.' THEN 6 WHEN 'jul.' THEN 7 WHEN 'ago.' THEN 8
                     WHEN 'set.' THEN 9 WHEN 'out.' THEN 10 WHEN 'nov.' THEN 11 WHEN 'dez.' THEN 12
                   END,
                   split_part(gd.release_date, '/', 1)::int) AS lancamento
          FROM game_details gd
          WHERE gd.release_date ~ '^[0-9]{1,2}/(jan|fev|mar|abr|mai|jun|jul|ago|set|out|nov|dez)[.]/[0-9]{4}$'
        )
        SELECT g.slug, g.title, g.cover_url, g.is_dlc, g.rank,
               barato.store_name, barato.price, barato.regular_price, barato.url,
               CASE WHEN barato.regular_price > 0 AND barato.price < barato.regular_price * 0.99
                    THEN ROUND((1 - barato.price / barato.regular_price) * 100)::integer ELSE 0 END AS discount_pct
        FROM datas d
        JOIN games g ON g.id = d.game_id
        JOIN LATERAL (
          SELECT o.store_name, o.price, o.regular_price, o.url
          FROM offers o
          WHERE o.game_id = g.id AND o.price > 0
            %s
          ORDER BY o.price ASC
          LIMIT 1
        ) barato ON true
        WHERE d.lancamento BETWEEN current_date - 60 AND current_date + 180
          AND g.rank IS NOT NULL AND g.rank <= 3000
          %s
          %s
          %s
        ORDER BY g.rank ASC, d.lancamento DESC
        LIMIT 30
        """.formatted(
            LojasBloqueadas.filtroSql("o"),
            ConteudosNaoJogos.filtroSql("g"),
            JogosBloqueados.filtroSql("g"),
            ClassificadorDlc.filtroApenasJogosSql("g"));

    return jdbc.sql(sql)
        .query((rs, linha) -> new DescontoJogo(
            rs.getString("slug"),
            rs.getString("title"),
            rs.getString("cover_url"),
            rs.getObject("is_dlc", Boolean.class),
            rs.getObject("rank", Integer.class),
            rs.getString("store_name"),
            rs.getBigDecimal("price"),
            rs.getBigDecimal("regular_price"),
            rs.getString("url"),
            rs.getObject("discount_pct", Integer.class)))
        .list();
  }
}
