package com.ofertagames.backend.configuracao;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Leva o motivo dos erros 4xx ate o frontend, em {@code {"error": "...", "status": 400}}.
 *
 * <p>O tratamento padrao do Spring Boot devolve {@code status/error/path} e <b>omite a mensagem</b>
 * ({@code server.error.include-message=never}). Toda {@code ResponseStatusException} com texto pro
 * usuario — "Esta URL de perfil ja esta em uso", "A imagem do bloco precisa ser enviada pelo site" —
 * chegava vazia, e a tela so conseguia mostrar "Nao foi possivel salvar" (issues #25 e #28).
 *
 * <p>Por que nao ligar {@code include-message=always}: aquilo expoe a mensagem de QUALQUER excecao,
 * inclusive detalhe de SQL num 500. Aqui so passa o {@code reason} de ResponseStatusException 4xx,
 * que e texto escrito de proposito pra quem usa o site; 5xx segue sem detalhe.
 */
@RestControllerAdvice
class TratadorErrosApi {

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<Map<String, Object>> tratar(ResponseStatusException erro) {
    int status = erro.getStatusCode().value();
    Map<String, Object> corpo = new LinkedHashMap<>();
    corpo.put("status", status);
    if (status < 500 && erro.getReason() != null && !erro.getReason().isBlank()) {
      corpo.put("error", erro.getReason());
    }
    return ResponseEntity.status(erro.getStatusCode()).headers(erro.getHeaders()).body(corpo);
  }
}
