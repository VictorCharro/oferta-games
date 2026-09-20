package com.ofertagames.backend.instantgaming;

import com.ofertagames.backend.comum.GeradorSlug;
import com.ofertagames.backend.notificacoes.RepositorioNotificacoes;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Instant Gaming como fonte extra de preco, em tres etapas independentes, cada uma com seu proprio
 * job agendado:
 *
 * <ol>
 *   <li>{@link #escanearCatalogo} — <b>descoberta</b>: varre ids numericos sequenciais de produto e
 *       guarda os que existem;</li>
 *   <li>{@link #casarComCatalogo} — <b>casamento</b>: liga produto descoberto a um jogo nosso pelo
 *       titulo normalizado, priorizando o top-2000 por rank como os demais jobs;</li>
 *   <li>{@link #atualizarPrecos} — <b>preco</b>: revisita os ja casados pra atualizar o valor.</li>
 * </ol>
 *
 * <p>A descoberta e por id sequencial, e nao por busca, porque o robots.txt deles bloqueia as
 * paginas de busca e permite as de produto — ver {@link ClienteInstantGaming}.
 *
 * <p>E a unica excecao de scraping do projeto: a Instant Gaming nao tem API publica nem esta na
 * ITAD, e o contato oficial pedindo acesso foi negado.
 */
@Service
public class ServicoInstantGaming {
  // Pausa entre requisicoes dentro de um lote de varredura, pra nao martelar o servidor deles.
  private static final long PAUSA_ENTRE_REQUISICOES_MS = 300;
  // Codigo de afiliado aprovado pela Instant Gaming; so entra na URL salva em offers (o link que
  // o usuario de fato clica), nunca na URL canonica usada internamente pra buscar/casar produtos.
  private static final String CODIGO_AFILIADO = "oferta-games";

  private final ClienteInstantGaming cliente;
  private final RepositorioInstantGaming repositorio;
  private final RepositorioNotificacoes notificacoes;

  ServicoInstantGaming(ClienteInstantGaming cliente, RepositorioInstantGaming repositorio, RepositorioNotificacoes notificacoes) {
    this.cliente = cliente;
    this.repositorio = repositorio;
    this.notificacoes = notificacoes;
  }

  public ResumoFilaInstantGaming resumirFila() {
    return new ResumoFilaInstantGaming(
        repositorio.buscarUltimoIdEscaneado(),
        repositorio.contarCatalogoDescoberto(),
        repositorio.contarCasados(),
        repositorio.contarPendentesCasamento());
  }

  public record ResumoFilaInstantGaming(
      int ultimoIdEscaneado,
      long catalogoDescoberto,
      long jogosCasados,
      long pendentesCasamento) {}

  /**
   * Varre os proximos {@code limite} ids de produto a partir do cursor salvo, guardando os que
   * existem.
   *
   * <p>O cursor <b>so anda pra frente</b> e avanca mesmo quando nada e encontrado. Duas
   * consequencias: ids que estavam fora de estoque na passagem nunca sao revisitados
   * automaticamente, e um produto que reabastecer depois so entra no catalogo se o cursor for
   * retrocedido na mao.
   *
   * <p>Pausa {@value #PAUSA_ENTRE_REQUISICOES_MS} ms entre requisicoes; um lote de {@code limite}
   * itens leva no minimo {@code limite * 300ms}, o que limita na pratica o tamanho da rodada.
   *
   * @return quantos produtos novos foram encontrados (nao quantos ids foram visitados)
   */
  public int escanearCatalogo(int limite) {
    int idAtual = repositorio.buscarUltimoIdEscaneado();
    int encontrados = 0;
    for (int i = 0; i < limite; i++) {
      idAtual++;
      int id = idAtual;
      if (cliente.buscarProduto(id).map(produto -> {
        repositorio.salvarNoCatalogo(id, produto.titulo(), GeradorSlug.porTitulo(produto.titulo()), produto.url());
        return true;
      }).orElse(false)) {
        encontrados++;
      }
      aguardarEntreRequisicoes();
    }
    repositorio.avancarCursor(idAtual);
    return encontrados;
  }

  public int casarComCatalogo(int limite) {
    int casados = 0;
    for (RepositorioInstantGaming.JogoParaCasar jogo : repositorio.listarPendentesCasamento(limite)) {
      Optional<String> url = repositorio.buscarUrlUnica(GeradorSlug.porTitulo(jogo.titulo()));
      if (url.isPresent()) {
        repositorio.salvarMatch(jogo.id(), url.get());
        casados++;
      }
    }
    return casados;
  }

  public int atualizarPrecos(int limite) {
    int atualizados = 0;
    for (RepositorioInstantGaming.JogoParaAtualizarPreco jogo : repositorio.listarPendentesAtualizacaoPreco(limite)) {
      if (atualizarPrecoDoJogo(jogo.id(), jogo.url())) {
        atualizados++;
      }
      aguardarEntreRequisicoes();
    }
    return atualizados;
  }

  /**
   * Atualiza o preco de um jogo na hora, para o refresh manual da pagina do jogo.
   *
   * <p>E uma unica requisicao, barata o suficiente pra rodar em linha com o refresh da ITAD. Se o
   * jogo ainda nao estiver casado, tenta casar <b>na hora</b> usando so o que ja foi descoberto
   * (consulta o banco, nao varre a Instant Gaming), em vez de esperar o proximo ciclo do job de
   * casamento.
   *
   * <p>O casamento encontrado aqui e persistido e vale dali em diante, mesmo que a busca de preco
   * dessa chamada falhe.
   *
   * @return {@code true} so quando um preco foi de fato gravado; {@code false} quando nao ha
   *     casamento possivel ou o produto esta indisponivel (caso em que a oferta antiga e removida)
   */
  public boolean atualizarPrecoImediato(long jogoId, String titulo) {
    Optional<String> url = repositorio.buscarInstantGamingUrl(jogoId);
    if (url.isEmpty()) {
      url = repositorio.buscarUrlUnica(GeradorSlug.porTitulo(titulo));
      if (url.isPresent()) {
        repositorio.salvarMatch(jogoId, url.get());
      }
    }
    return url.isPresent() && atualizarPrecoDoJogo(jogoId, url.get());
  }

  private boolean atualizarPrecoDoJogo(long jogoId, String url) {
    Optional<ClienteInstantGaming.ProdutoInstantGaming> produto = cliente.buscarProdutoPorUrl(url);
    if (produto.isEmpty()) {
      // Fora de estoque ou removido: tira a oferta antiga em vez de deixar um preco desatualizado
      // parecendo disponivel.
      repositorio.removerOferta(jogoId);
      return false;
    }
    var anterior = repositorio.precoMinimo(jogoId);
    repositorio.salvarPreco(jogoId, produto.get().preco(), produto.get().moeda(), comLinkAfiliado(produto.get().url()));
    notificacoes.registrarQueda(jogoId, anterior, repositorio.precoMinimo(jogoId));
    return true;
  }

  /**
   * Acrescenta o codigo de afiliado a URL.
   *
   * <p>So e aplicado na URL gravada em {@code offers} — o link que o usuario clica. A URL canonica
   * usada internamente para buscar e casar produtos ({@code games.instant_gaming_url},
   * {@code instant_gaming_catalog.url}) fica sempre limpa; misturar as duas quebraria o casamento
   * por URL.
   */
  private static String comLinkAfiliado(String url) {
    return url + (url.contains("?") ? "&" : "?") + "igr=" + CODIGO_AFILIADO;
  }

  private static void aguardarEntreRequisicoes() {
    try {
      Thread.sleep(PAUSA_ENTRE_REQUISICOES_MS);
    } catch (InterruptedException erro) {
      Thread.currentThread().interrupt();
    }
  }
}
