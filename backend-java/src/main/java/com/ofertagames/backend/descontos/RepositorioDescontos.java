package com.ofertagames.backend.descontos;

import com.ofertagames.backend.comum.ClassificadorDlc;
import com.ofertagames.backend.comum.ConfiguracaoCache;
import com.ofertagames.backend.comum.ConteudosNaoJogos;
import com.ofertagames.backend.comum.JogosBloqueados;
import com.ofertagames.backend.comum.LojasBloqueadas;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RepositorioDescontos {
  private final JdbcClient jdbc;

  RepositorioDescontos(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // Query pesada (DISTINCT ON + join em offers inteira) chamada 2x a cada carregamento da home.
  // TTL definido em ConfiguracaoCache (10min).
  // price=0 entra sempre como 100% off (mesmo com regular_price nulo/0): jogos permanentemente
  // gratis nao tem "preco normal" pra calcular desconto a partir dele, mas ainda sao gratuitos
  // de verdade (ex: giveaway numa loja com o jogo ainda pago em outra).
  // tipo=dlc busca DLCs diretamente (WHERE g.is_dlc), em vez de confiar em uma amostra generica
  // conter DLCs suficientes: como DLCs sao uma fatia pequena do catalogo, um "top 200 por desconto"
  // sem esse filtro quase nunca traz DLC nenhuma nas primeiras posicoes (jogos base dominam o
  // ranking), entao filtrar so depois (no frontend) deixava o carrossel de DLCs quase vazio mesmo
  // havendo milhares de DLCs com desconto ativo no catalogo.
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

    String sql = """
        SELECT * FROM (
          SELECT DISTINCT ON (g.id)
            g.slug,
            g.title,
            g.cover_url,
            g.is_dlc,
            g.rank,
            o.store_name,
            o.price,
            o.regular_price,
            o.url,
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
          ORDER BY g.id, o.price ASC
        ) sub
        ORDER BY %s
        LIMIT :tamanho
        """.formatted(
            LojasBloqueadas.filtroSql("o"),
            ConteudosNaoJogos.filtroSql("g"),
            JogosBloqueados.filtroSql("g"),
            filtroTipo,
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
