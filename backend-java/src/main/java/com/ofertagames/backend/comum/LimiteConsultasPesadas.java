package com.ofertagames.backend.comum;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Teto de consultas pesadas <b>simultaneas</b> no banco do catalogo.
 *
 * <p>Normalizar os parametros ({@link ParametrosPublicos}) reduz as chaves de cache, mas filtros
 * legitimos (preco, desconto, busca, lojas) ainda geram combinacoes que ninguem aqueceu, e cada uma
 * paga uma agregacao do catalogo inteiro. Sem teto, uma rajada dessas — de proposito ou nao —
 * enfileira dezenas de agregacoes no unico vCPU da VM, e ai ate as paginas <b>cacheadas</b> ficam
 * lentas, porque o processo inteiro disputa a mesma CPU (issue #16).
 *
 * <p>Com o teto, o excesso espera um pouco e, se ainda nao houver vaga, recebe 503. Quem pede algo
 * ja em cache nem passa por aqui (o {@code @Cacheable} responde antes de entrar no metodo), entao a
 * Home e a primeira pagina do catalogo continuam no ar mesmo sob rajada.
 *
 * <p>So envolve consultas que agregam o catalogo inteiro. As pre-paginadas (~30ms) ficam de fora de
 * proposito, pra navegacao comum nao disputar vaga com filtro caro.
 */
@Component
public class LimiteConsultasPesadas {
  private static final Logger logger = LoggerFactory.getLogger(LimiteConsultasPesadas.class);

  /** 2 num vCPU: uma consulta pesada nunca bloqueia sozinha, e mais que isso so disputa a CPU. */
  static final int VAGAS = 2;

  /** Menor que o timeout do SSR, pra pagina receber o 503 e renderizar o estado de erro em vez de travar. */
  static final long ESPERA_MAXIMA_SEGUNDOS = 15;

  private final Semaphore vagas = new Semaphore(VAGAS, true);

  public <T> T executar(String descricao, Supplier<T> consulta) {
    boolean conseguiu;
    try {
      conseguiu = vagas.tryAcquire(ESPERA_MAXIMA_SEGUNDOS, TimeUnit.SECONDS);
    } catch (InterruptedException erro) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Consulta interrompida");
    }
    if (!conseguiu) {
      logger.warn("Consulta pesada recusada apos {}s sem vaga: {}", ESPERA_MAXIMA_SEGUNDOS, descricao);
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Muitas consultas no momento. Tente novamente em instantes.");
    }
    try {
      return consulta.get();
    } finally {
      vagas.release();
    }
  }

  int vagasLivres() {
    return vagas.availablePermits();
  }
}
