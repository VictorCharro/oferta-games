package com.ofertagames.backend.sincronizacao;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpServerErrorException;

@RestController
@RequestMapping("/api/sync")
public class ControladorSincronizacao {
  private final ServicoSincronizacao sincronizacao;
  private final String chaveSecreta;

  ControladorSincronizacao(
      ServicoSincronizacao sincronizacao,
      @Value("${app.sync.secret-key}") String chaveSecreta
  ) {
    this.sincronizacao = sincronizacao;
    this.chaveSecreta = chaveSecreta;
  }

  @PostMapping
  ResponseEntity<?> sincronizar(
      @RequestHeader(value = "X-Sync-Key", required = false) String chaveInformada,
      @RequestParam(defaultValue = "0") int page
  ) {
    // Comparacao em tempo constante (issue #30): equals() para no primeiro byte diferente, e o tempo
    // de resposta vira um oraculo pra descobrir a chave aos poucos.
    if (chaveSecreta == null || chaveSecreta.isBlank() || chaveInformada == null
        || !java.security.MessageDigest.isEqual(
            chaveSecreta.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            chaveInformada.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
      return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
    }
    try {
      return ResponseEntity.ok(sincronizacao.sincronizarPagina(page));
    } catch (RuntimeException erro) {
      HttpStatus status = erro instanceof HttpServerErrorException
          ? HttpStatus.SERVICE_UNAVAILABLE
          : HttpStatus.INTERNAL_SERVER_ERROR;
      return ResponseEntity.status(status).body(Map.of(
          "error", "Sync failed",
          "page", page,
          "type", erro.getClass().getSimpleName(),
          "message", erro.getMessage() == null ? "" : erro.getMessage(),
          "rootCause", mensagemCausaRaiz(erro)));
    }
  }

  private String mensagemCausaRaiz(Throwable erro) {
    Throwable causa = erro;
    while (causa.getCause() != null && causa.getCause() != causa) {
      causa = causa.getCause();
    }
    return causa.getMessage() == null ? "" : causa.getMessage();
  }
}
