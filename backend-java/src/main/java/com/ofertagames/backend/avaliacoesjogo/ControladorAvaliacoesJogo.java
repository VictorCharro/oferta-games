package com.ofertagames.backend.avaliacoesjogo;

import com.ofertagames.backend.autenticacao.ServicoAutenticacao;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/games/{slug}/reviews")
public class ControladorAvaliacoesJogo {
  private final ServicoAutenticacao autenticacao;
  private final ServicoAvaliacoesJogo servico;

  ControladorAvaliacoesJogo(ServicoAutenticacao autenticacao, ServicoAvaliacoesJogo servico) {
    this.autenticacao = autenticacao;
    this.servico = servico;
  }

  @GetMapping
  ResponseEntity<?> listar(@PathVariable String slug, @RequestHeader(value = "Authorization", required = false) String autorizacao) {
    String visitanteId = autenticacao.buscarUsuarioPeloCabecalho(autorizacao).orElse(null);
    return servico.buscar(slug, visitanteId)
        .<ResponseEntity<?>>map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "Jogo não encontrado")));
  }

  @PostMapping
  ResponseEntity<?> avaliar(
      @PathVariable String slug,
      @RequestHeader(value = "Authorization", required = false) String autorizacao,
      @RequestBody(required = false) RequisicaoAvaliacao requisicao
  ) {
    Optional<String> usuarioId = autenticacao.buscarUsuarioPeloCabecalho(autorizacao);
    if (usuarioId.isEmpty()) return naoAutenticado();
    try {
      servico.salvar(slug, usuarioId.get(), requisicao);
      return ResponseEntity.status(201).body(Map.of("ok", true));
    } catch (ServicoAvaliacoesJogo.JogoNaoEncontradoException erro) {
      return ResponseEntity.status(404).body(Map.of("error", "Jogo não encontrado"));
    } catch (ServicoAvaliacoesJogo.AvaliacaoInvalidaException erro) {
      return ResponseEntity.badRequest().body(Map.of("error", erro.getMessage()));
    }
  }

  @DeleteMapping
  ResponseEntity<?> remover(@PathVariable String slug, @RequestHeader(value = "Authorization", required = false) String autorizacao) {
    Optional<String> usuarioId = autenticacao.buscarUsuarioPeloCabecalho(autorizacao);
    if (usuarioId.isEmpty()) return naoAutenticado();
    try {
      servico.excluir(slug, usuarioId.get());
      return ResponseEntity.ok(Map.of("ok", true));
    } catch (ServicoAvaliacoesJogo.JogoNaoEncontradoException erro) {
      return ResponseEntity.status(404).body(Map.of("error", "Jogo não encontrado"));
    }
  }

  @PostMapping("/{reviewId}/voto")
  ResponseEntity<?> votar(
      @PathVariable String slug,
      @PathVariable long reviewId,
      @RequestHeader(value = "Authorization", required = false) String autorizacao,
      @RequestBody(required = false) RequisicaoVoto requisicao
  ) {
    Optional<String> usuarioId = autenticacao.buscarUsuarioPeloCabecalho(autorizacao);
    if (usuarioId.isEmpty()) return naoAutenticado();
    if (requisicao == null || requisicao.util() == null) {
      return ResponseEntity.badRequest().body(Map.of("error", "Informe util: true ou false"));
    }
    try {
      servico.votar(reviewId, usuarioId.get(), requisicao.util());
      return ResponseEntity.ok(Map.of("ok", true));
    } catch (ServicoAvaliacoesJogo.AvaliacaoNaoEncontradaException erro) {
      return ResponseEntity.status(404).body(Map.of("error", "Avaliação não encontrada"));
    }
  }

  private static ResponseEntity<?> naoAutenticado() {
    return ResponseEntity.status(401).body(Map.of("error", "Não autenticado"));
  }
}
