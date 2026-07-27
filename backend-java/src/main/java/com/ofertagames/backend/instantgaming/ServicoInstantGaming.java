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

  private final ClienteInstantGaming cliente;
  private final RepositorioInstantGaming repositorio;

  ServicoInstantGaming(ClienteInstantGaming cliente, RepositorioInstantGaming repositorio) {
    this.cliente = cliente;
    this.repositorio = repositorio;
  }

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
  // barata o suficiente pra rodar em linha com o refresh da ITAD.
  public boolean atualizarPrecoImediato(long jogoId) {
    Optional<String> url = repositorio.buscarInstantGamingUrl(jogoId);
    return url.isPresent() && atualizarPrecoDoJogo(jogoId, url.get());
  }

  private boolean atualizarPrecoDoJogo(long jogoId, String url) {
    return cliente.buscarProdutoPorUrl(url)
        .map(produto -> {
          repositorio.salvarPreco(jogoId, produto.preco(), produto.moeda(), produto.url());
          return true;
        })
        .orElse(false);
  }

  private static void aguardarEntreRequisicoes() {
    try {
      Thread.sleep(PAUSA_ENTRE_REQUISICOES_MS);
    } catch (InterruptedException erro) {
      Thread.currentThread().interrupt();
    }
  }
}
