package com.ofertagames.backend.erros;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.HttpMethod;

class RegistroErrosTest {

  @Test
  void mesmoErroEmDeploysEIdsDiferentesTemMesmaAssinatura() {
    String a = RegistroErros.assinatura("navegador", "Cannot read properties of undefined (reading 'titulo')",
        "TypeError: x\n    at h (https://ofertagames.vercel.app/chunk-ABC123XY.js:1:4521)\n    at y (main-QWE987.js:2:10)");
    String b = RegistroErros.assinatura("navegador", "Cannot read properties of undefined (reading 'titulo')",
        "TypeError: x\n    at h (https://ofertagames.vercel.app/chunk-ZZZ999AA.js:1:9999)\n    at y (main-RTY111.js:5:77)");
    assertEquals(a, b);
  }

  @Test
  void errosDiferentesOuDeOrigemDiferenteNaoSeMisturam() {
    String base = RegistroErros.assinatura("navegador", "Falha A", null);
    assertNotEquals(base, RegistroErros.assinatura("navegador", "Falha B", null));
    assertNotEquals(base, RegistroErros.assinatura("servidor", "Falha A", null));
  }

  @Test
  void observadorIgnoraErroComStatusDeProposito() throws Exception {
    assertFalse(ObservadorExcecoes.deveRegistrar(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    assertTrue(ObservadorExcecoes.deveRegistrar(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)));
    assertFalse(ObservadorExcecoes.deveRegistrar(new NoResourceFoundException(HttpMethod.GET, "/x")));
    assertTrue(ObservadorExcecoes.deveRegistrar(new IllegalStateException("bug")));
  }

  @Test
  void pilhaGuardaSoPrimeiraLinhaENossoCodigo() {
    String pilha = "java.lang.IllegalStateException: bug\n\tat org.springframework.X.y(X.java:1)\n\tat com.ofertagames.backend.Z.w(Z.java:9)\nCaused by: java.io.IOException: io";
    assertEquals("java.lang.IllegalStateException: bug\nat com.ofertagames.backend.Z.w(Z.java:9)\nCaused by: java.io.IOException: io\n",
        ObservadorExcecoes.pilhaDoProjeto(pilha));
  }
}
