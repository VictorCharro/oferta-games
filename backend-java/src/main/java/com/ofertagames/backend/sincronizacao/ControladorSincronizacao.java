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
    if (chaveSecreta == null || chaveSecreta.isBlank() || !chaveSecreta.equals(chaveInformada)) {
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
          "message", erro.getMessage() == null ? "" : erro.getMessage()));
    }
  }
}
