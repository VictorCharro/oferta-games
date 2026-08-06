package com.ofertagames.backend.instantgaming;

import com.ofertagames.backend.comum.GeradorSlug;
import java.util.Optional;
import org.springframework.stereotype.Service;

// Instant Gaming nao tem API publica (contato feito, negado). Descoberta e feita varrendo
// paginas de produto por id numerico sequencial (permitido pelo robots.txt deles; a busca do
// site e que e bloqueada), casamento por titulo normalizado exato prioriza o rank do catalogo,
// igual aos outros jobs de coleta.
@Service
public class ServicoInstantGaming {
  // Pausa entre requisicoes dentro de um lote de varredura, pra nao martelar o servidor deles.
  private static final long PAUSA_ENTRE_REQUISICOES_MS = 300;
  // Codigo de afiliado aprovado pela Instant Gaming; so entra na URL salva em offers (o link que
  // o usuario de fato clica), nunca na URL canonica usada internamente pra buscar/casar produtos.
  private static final String CODIGO_AFILIADO = "oferta-games";

  private final ClienteInstantGaming cliente;
  private final RepositorioInstantGaming repositorio;

  ServicoInstantGaming(ClienteInstantGaming cliente, RepositorioInstantGaming repositorio) {
    this.cliente = cliente;
    this.repositorio = repositorio;
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

  // Usado pelo refresh manual ("Atualizar precos") na pagina do jogo: uma unica requisicao,
  // barata o suficiente pra rodar em linha com o refresh da ITAD. Se o jogo ainda nao tiver
  // casamento, tenta casar na hora com o que ja foi descoberto ate agora (so consulta o banco,
  // nao busca na Instant Gaming) em vez de esperar o proximo ciclo do job de casamento.
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
    repositorio.salvarPreco(jogoId, produto.get().preco(), produto.get().moeda(), comLinkAfiliado(produto.get().url()));
    return true;
  }

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
