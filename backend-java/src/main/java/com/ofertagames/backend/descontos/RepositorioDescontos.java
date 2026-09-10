package com.ofertagames.backend.descontos;

import com.ofertagames.backend.comum.ClassificadorDlc;
import com.ofertagames.backend.comum.ConfiguracaoCache;
import com.ofertagames.backend.comum.ConteudosNaoJogos;
import com.ofertagames.backend.comum.JogosBloqueados;
import com.ofertagames.backend.comum.LojasBloqueadas;
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

  RepositorioDescontos(@Qualifier("catalogo") JdbcClient jdbc) {
    this.jdbc = jdbc;
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
   *   <li>{@code price = 0} conta sempre como 100% off, mesmo sem {@code regular_price}: jogo
   *       permanentemente gratuito nao tem preco normal de onde derivar desconto, mas e gratuito de
   *       verdade;</li>
   *   <li>desconto so conta a partir de 1% ({@code price < regular_price * 0.99}), pra ruido de
   *       conversao cambial nao virar "promocao";</li>
   *   <li>{@code tipo = "dlc"} filtra DLC <b>na query</b>. Filtrar depois, no frontend, deixava o
   *       carrossel de DLCs quase vazio: como DLC e fatia pequena do catalogo, os jogos base
   *       dominam o topo do ranking geral.</li>
   * </ul>
   *
   * <p>Query cara ({@code DISTINCT ON} + join na tabela de ofertas inteira) e chamada varias vezes
   * por carregamento da Home, por isso o cache de 10 min e o reaquecimento apos cada rodada de
   * precos. Ver {@link ConfiguracaoCache}.
   *
   * @param ordenacao {@code rank} (relevancia primeiro) ou qualquer outro valor para ordenar por
   *     maior desconto
   * @param tipo {@code game}, {@code dlc}, ou outro valor para nao filtrar
   */
  @Cacheable(ConfiguracaoCache.CACHE_DESCONTOS)
  public List<DescontoJogo> listarMelhores(int tamanho, String ordenacao, String tipo) {
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
    String sql = """
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
              o.price = 0
              OR (o.regular_price IS NOT NULL AND o.regular_price > 0 AND o.price < o.regular_price * 0.99)
            )
            %s
            %s
            %s
            %s
          ORDER BY g.id, (CASE WHEN o.price = 0 THEN 100 ELSE ROUND((1 - o.price / o.regular_price) * 100)::integer END) DESC
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
        LIMIT :tamanho
        """.formatted(
            LojasBloqueadas.filtroSql("o"),
            ConteudosNaoJogos.filtroSql("g"),
            JogosBloqueados.filtroSql("g"),
            filtroTipo,
            LojasBloqueadas.filtroSql("o"),
            ordenarPor);

    return jdbc.sql(sql)
        .param("tamanho", tamanho)
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
