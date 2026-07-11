package com.ofertagames.backend.sincronizacao;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
