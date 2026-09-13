package com.ofertagames.backend.notificacoes;

import com.ofertagames.backend.autenticacao.ServicoAutenticacao;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class ControladorNotificacoes {
  private final ServicoAutenticacao autenticacao;
  private final RepositorioNotificacoes notificacoes;

  ControladorNotificacoes(ServicoAutenticacao autenticacao, RepositorioNotificacoes notificacoes) {
    this.autenticacao = autenticacao;
    this.notificacoes = notificacoes;
  }

  @GetMapping
  ResponseEntity<?> listar(@RequestHeader(value = "Authorization", required = false) String autorizacao) {
    var usuario = usuario(autorizacao);
    if (usuario.isEmpty()) return naoAutenticado();
    return ResponseEntity.ok(notificacoes.listar(usuario.get(), 30));
  }

  @GetMapping("/unread-count")
  ResponseEntity<?> contarNaoLidas(@RequestHeader(value = "Authorization", required = false) String autorizacao) {
    var usuario = usuario(autorizacao);
    if (usuario.isEmpty()) return naoAutenticado();
    return ResponseEntity.ok(Map.of("count", notificacoes.contarNaoLidas(usuario.get())));
  }

  @PatchMapping("/{id}/read")
  ResponseEntity<?> marcarComoLida(@RequestHeader(value = "Authorization", required = false) String autorizacao, @PathVariable long id) {
    var usuario = usuario(autorizacao);
    if (usuario.isEmpty()) return naoAutenticado();
    notificacoes.marcarComoLida(usuario.get(), id);
    return ResponseEntity.noContent().build();
  }

  @PatchMapping("/read-all")
  ResponseEntity<?> marcarTodasComoLidas(@RequestHeader(value = "Authorization", required = false) String autorizacao) {
    var usuario = usuario(autorizacao);
    if (usuario.isEmpty()) return naoAutenticado();
    notificacoes.marcarTodasComoLidas(usuario.get());
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{id}")
  ResponseEntity<?> remover(@RequestHeader(value = "Authorization", required = false) String autorizacao, @PathVariable long id) {
    var usuario = usuario(autorizacao);
    if (usuario.isEmpty()) return naoAutenticado();
    notificacoes.remover(usuario.get(), id);
    return ResponseEntity.noContent().build();
  }

  private java.util.Optional<String> usuario(String autorizacao) { return autenticacao.buscarUsuarioPeloCabecalho(autorizacao); }
  private static ResponseEntity<Map<String, String>> naoAutenticado() { return ResponseEntity.status(401).body(Map.of("error", "Não autenticado")); }
}
