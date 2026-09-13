package com.ofertagames.backend.comum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class LimiteConsultasPesadasTest {

  @Test
  void devolveOResultadoELiberaAVaga() {
    LimiteConsultasPesadas limite = new LimiteConsultasPesadas();
    assertEquals("ok", limite.executar("teste", () -> "ok"));
    assertEquals(LimiteConsultasPesadas.VAGAS, limite.vagasLivres());
  }

  @Test
  void liberaAVagaMesmoQuandoAConsultaFalha() {
    LimiteConsultasPesadas limite = new LimiteConsultasPesadas();
    assertThrows(IllegalStateException.class, () -> limite.executar("teste", () -> {
      throw new IllegalStateException("banco caiu");
    }));
    assertEquals(LimiteConsultasPesadas.VAGAS, limite.vagasLivres());
  }

  @Test
  void naoPassaDoNumeroDeVagasSimultaneas() throws Exception {
    LimiteConsultasPesadas limite = new LimiteConsultasPesadas();
    CountDownLatch dentro = new CountDownLatch(LimiteConsultasPesadas.VAGAS);
    CountDownLatch soltar = new CountDownLatch(1);
    for (int i = 0; i < LimiteConsultasPesadas.VAGAS; i++) {
      Thread.ofVirtual().start(() -> limite.executar("lenta", () -> {
        dentro.countDown();
        try {
          soltar.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return null;
      }));
    }
    dentro.await(5, TimeUnit.SECONDS);
    assertEquals(0, limite.vagasLivres());
    soltar.countDown();
  }

  @Test
  void recusaCom503QuandoAThreadEInterrompidaEsperandoVaga() throws Exception {
    LimiteConsultasPesadas limite = new LimiteConsultasPesadas();
    CountDownLatch dentro = new CountDownLatch(LimiteConsultasPesadas.VAGAS);
    CountDownLatch soltar = new CountDownLatch(1);
    for (int i = 0; i < LimiteConsultasPesadas.VAGAS; i++) {
      Thread.ofVirtual().start(() -> limite.executar("lenta", () -> {
        dentro.countDown();
        try {
          soltar.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return null;
      }));
    }
    dentro.await(5, TimeUnit.SECONDS);
    // Sem vaga: a espera de 15s nao cabe num teste, entao interrompe a thread pra forcar a recusa.
    Thread.currentThread().interrupt();
    ResponseStatusException erro = assertThrows(ResponseStatusException.class,
        () -> limite.executar("excedente", () -> "nunca"));
    assertEquals(503, erro.getStatusCode().value());
    Thread.interrupted();
    soltar.countDown();
  }
}
