package com.ofertagames.backend.favoritos;

import com.ofertagames.backend.autenticacao.ServicoAutenticacao;
import com.ofertagames.backend.atividadesperfil.RepositorioAtividadesPerfil;
import com.ofertagames.backend.jogos.RepositorioJogos;
import java.util.Map;
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
@RequestMapping("/api/favorites")
public class ControladorFavoritos {
  private final ServicoAutenticacao autenticacao;
  private final RepositorioFavoritos favoritos;
  private final RepositorioJogos jogos;
  private final RepositorioAtividadesPerfil atividades;

  ControladorFavoritos(ServicoAutenticacao autenticacao, RepositorioFavoritos favoritos, RepositorioJogos jogos, RepositorioAtividadesPerfil atividades) {
    this.autenticacao = autenticacao;
    this.favoritos = favoritos;
    this.jogos = jogos;
    this.atividades = atividades;
  }

  @GetMapping
  ResponseEntity<?> listar(@RequestHeader(value = "Authorization", required = false) String autorizacao) {
    return autenticacao.buscarUsuarioPeloCabecalho(autorizacao)
        .<ResponseEntity<?>>map(usuarioId -> ResponseEntity.ok(favoritos.listarPorUsuario(usuarioId)))
        .orElseGet(() -> ResponseEntity.status(401).body(Map.of("error", "Nao autenticado")));
  }

  @PostMapping
  ResponseEntity<?> adicionar(
      @RequestHeader(value = "Authorization", required = false) String autorizacao,
      @RequestBody(required = false) RequisicaoFavorito requisicao
  ) {
    var usuarioId = autenticacao.buscarUsuarioPeloCabecalho(autorizacao);
    if (usuarioId.isEmpty()) {
      return ResponseEntity.status(401).body(Map.of("error", "Nao autenticado"));
    }
    if (requisicao == null || requisicao.slug() == null || requisicao.slug().isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("error", "slug e obrigatorio"));
    }

    var jogoId = jogos.buscarIdPorSlug(requisicao.slug());
    if (jogoId.isEmpty()) {
      return ResponseEntity.status(404).body(Map.of("error", "Jogo nao encontrado"));
    }

    if (favoritos.adicionar(usuarioId.get(), jogoId.get(), requisicao.targetPrice())) {
      atividades.registrar(usuarioId.get(), "MONITORAMENTO_ADICIONADO", jogoId.get());
    }
    return ResponseEntity.status(201).body(Map.of("ok", true));
  }

  @DeleteMapping("/{slug}")
  ResponseEntity<?> remover(
      @RequestHeader(value = "Authorization", required = false) String autorizacao,
      @PathVariable String slug
  ) {
    var usuarioId = autenticacao.buscarUsuarioPeloCabecalho(autorizacao);
    if (usuarioId.isEmpty()) {
      return ResponseEntity.status(401).body(Map.of("error", "Nao autenticado"));
    }

    var jogoId = jogos.buscarIdPorSlug(slug);
    if (jogoId.isEmpty()) {
      return ResponseEntity.status(404).body(Map.of("error", "Jogo nao encontrado"));
    }

    if (favoritos.remover(usuarioId.get(), jogoId.get())) {
      atividades.registrar(usuarioId.get(), "MONITORAMENTO_REMOVIDO", jogoId.get());
    }
    return ResponseEntity.ok(Map.of("ok", true));
  }
}
