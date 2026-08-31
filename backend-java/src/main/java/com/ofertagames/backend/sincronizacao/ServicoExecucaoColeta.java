package com.ofertagames.backend.sincronizacao;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Envelope comum de toda coleta: adquire a trava, registra o estado (em execucao / concluida /
 * falhou) e libera a trava no fim.
 *
 * <p>A trava e <b>uma so pra todos os 8 tipos</b> de coleta ({@code BLOQUEIO_COLETA}), e vive no
 * banco ({@code sync_locks}) — nao em memoria. Isso garante que dois jobs nunca rodem em paralelo
 * nem mesmo entre instancias diferentes, situacao que acontece de verdade durante um deploy, com
 * a instancia antiga e a nova no ar ao mesmo tempo.
 *
 * <p>Efeito colateral do desenho: um job so bloqueia todos os outros enquanto roda. Se a coleta de
 * precos demora, as demais daquele intervalo sao <b>puladas</b> (nao enfileiradas) e so tentam de
 * novo no proximo disparo.
 */
@Service
public class ServicoExecucaoColeta {
  private static final Logger logger = LoggerFactory.getLogger(ServicoExecucaoColeta.class);
  private static final String BLOQUEIO_COLETA = "coleta-oferta-games";

  private final RepositorioControleColeta controle;
  private final EstadoColeta estado;

  ServicoExecucaoColeta(RepositorioControleColeta controle, EstadoColeta estado) {
    this.controle = controle;
    this.estado = estado;
  }

  /**
   * Roda uma coleta sob a trava compartilhada, registrando inicio, conclusao ou falha em
   * {@link EstadoColeta} (persistido em {@code coleta_status}, o que alimenta a tela de admin).
   *
   * @param tipo identificador do job ({@code precos}, {@code steam}, {@code detalhes},
   *     {@code conquistas-catalogo}, {@code instant-gaming-*}) — e a chave do estado e precisa
   *     bater com o que o controlador de admin usa
   * @return {@code false} quando a trava ja estava tomada e a coleta <b>nao rodou</b>. Nao e erro:
   *     e o caminho normal quando outro job esta em andamento
   * @throws RuntimeException repassa qualquer erro da coleta apos registrar a falha no estado; a
   *     trava e sempre liberada
   */
  public boolean executar(String tipo, Supplier<ResultadoRodadaColeta> coleta) {
    if (!controle.tentarAdquirir(BLOQUEIO_COLETA)) {
      logger.info("Coleta de {} ignorada: outra coleta ainda esta em andamento", tipo);
      return false;
    }

    long inicio = System.nanoTime();
    estado.iniciar(tipo);
    try {
      ResultadoRodadaColeta resultado = coleta.get();
      estado.concluir(tipo, resultado, duracaoEmMs(inicio));
      return true;
    } catch (RuntimeException erro) {
      estado.falhar(tipo, erro, duracaoEmMs(inicio));
      throw erro;
    } finally {
      controle.liberar(BLOQUEIO_COLETA);
    }
  }

  public EstadoColeta.RegistroColeta consultar(String tipo) {
    return estado.consultar(tipo);
  }

  private static long duracaoEmMs(long inicio) {
    return (System.nanoTime() - inicio) / 1_000_000;
  }
}
