package com.ofertagames.backend.sincronizacao;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    return ResponseEntity.ok(sincronizacao.sincronizarPagina(page));
  }
}
