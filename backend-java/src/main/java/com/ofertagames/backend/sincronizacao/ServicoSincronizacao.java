package com.ofertagames.backend.sincronizacao;

import com.ofertagames.backend.itad.ClienteItad;
import com.ofertagames.backend.itad.ItemOfertaItad;
import com.ofertagames.backend.itad.RespostaOfertasItad;
import com.ofertagames.backend.jogos.ServicoCatalogo;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class ServicoSincronizacao {
  private static final int TAMANHO_PAGINA = 50;
  private static final int LIMITE_BACKFILL_STEAM = 50;

  private final ClienteItad itad;
  private final ServicoCatalogo catalogo;

  ServicoSincronizacao(ClienteItad itad, ServicoCatalogo catalogo) {
    this.itad = itad;
    this.catalogo = catalogo;
  }

  public ResultadoSincronizacao sincronizarPagina(int pagina) {
    int paginaSegura = Math.max(0, pagina);
    int deslocamento = paginaSegura * TAMANHO_PAGINA;
    RespostaOfertasItad resposta = itad.buscarOfertas(TAMANHO_PAGINA, deslocamento);
    List<ItemOfertaItad> itens = resposta == null ? List.of() : Objects.requireNonNullElse(resposta.list(), List.of());

    int sincronizadas = 0;
    for (int indice = 0; indice < itens.size(); indice++) {
      sincronizadas += catalogo.salvarOfertaDoSync(itens.get(indice), deslocamento + indice);
    }

    boolean temMais = Boolean.TRUE.equals(resposta == null ? null : resposta.hasMore());
    int steamAtualizados = catalogo.preencherMetadadosSteam(LIMITE_BACKFILL_STEAM);
    return new ResultadoSincronizacao(true, sincronizadas, temMais, temMais ? paginaSegura + 1 : null, steamAtualizados);
  }
}
