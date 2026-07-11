package com.ofertagames.backend.sincronizacao;

import com.ofertagames.backend.itad.ClienteItad;
import com.ofertagames.backend.itad.ItemOfertaItad;
import com.ofertagames.backend.itad.RespostaOfertasItad;
import com.ofertagames.backend.jogos.RepositorioJogos;
import com.ofertagames.backend.jogos.ServicoCatalogo;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ServicoSincronizacao {
  private static final int TAMANHO_PAGINA = 50;
  private static final int LIMITE_BACKFILL_STEAM = 10;
  private static final int LIMITE_REVALIDACAO_ANTIGOS = 550;
  private static final int LIMITE_REVALIDACAO_TOP_RANK = 20;
  private static final Logger logger = LoggerFactory.getLogger(ServicoSincronizacao.class);

  private final ClienteItad itad;
  private final ServicoCatalogo catalogo;
  private final RepositorioJogos jogos;

  ServicoSincronizacao(ClienteItad itad, ServicoCatalogo catalogo, RepositorioJogos jogos) {
    this.itad = itad;
    this.catalogo = catalogo;
    this.jogos = jogos;
  }

  public ResultadoSincronizacao sincronizarPagina(int pagina) {
    int paginaSegura = Math.max(0, pagina);
    int deslocamento = paginaSegura * TAMANHO_PAGINA;
    RespostaOfertasItad resposta = itad.buscarOfertas(TAMANHO_PAGINA, deslocamento);
    List<ItemOfertaItad> itens = resposta == null ? List.of() : Objects.requireNonNullElse(resposta.list(), List.of());

    int sincronizadas = catalogo.salvarOfertasDoSync(itens, deslocamento);
    int ignoradas = Math.max(0, itens.size() - sincronizadas);

    boolean temMais = Boolean.TRUE.equals(resposta == null ? null : resposta.hasMore());
    int steamAtualizados;
    try {
      steamAtualizados = catalogo.preencherMetadadosSteam(LIMITE_BACKFILL_STEAM);
    } catch (RuntimeException e) {
      logger.warn("Erro ao preencher metadados Steam", e);
      steamAtualizados = 0;
    }

    revalidarPrecosTopRank();
    revalidarPrecosAntigos();

    return new ResultadoSincronizacao(true, sincronizadas, ignoradas, temMais, temMais ? paginaSegura + 1 : null, steamAtualizados);
  }

  private void revalidarPrecosTopRank() {
    try {
      List<String> slugs = jogos.listarSlugsTopRank(LIMITE_REVALIDACAO_TOP_RANK);
      for (String slug : slugs) {
        try {
          catalogo.atualizarPrecos(slug);
        } catch (Exception e) {
          logger.warn("Erro ao revalidar preço do slug top rank '{}'", slug, e);
        }
      }
    } catch (Exception e) {
      logger.error("Erro ao buscar slugs top rank para revalidação", e);
    }
  }

  private void revalidarPrecosAntigos() {
    try {
      List<String> slugs = jogos.listarSlugsAntigos(LIMITE_REVALIDACAO_ANTIGOS);
      for (String slug : slugs) {
        try {
          catalogo.atualizarPrecos(slug);
        } catch (Exception e) {
          logger.warn("Erro ao revalidar preço do slug antigo '{}'", slug, e);
        }
      }
    } catch (Exception e) {
      logger.error("Erro ao buscar slugs antigos para revalidação", e);
    }
  }
}