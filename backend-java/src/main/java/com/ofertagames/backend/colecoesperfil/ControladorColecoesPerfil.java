package com.ofertagames.backend.colecoesperfil;

import com.ofertagames.backend.autenticacao.ServicoAutenticacao;
import com.ofertagames.backend.jogos.RepositorioJogos;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profile-collections")
public class ControladorColecoesPerfil {
  private static final int MAXIMO_COLECOES = 20;
  private static final int MAXIMO_ITENS = 200;
  private static final int MAXIMO_CARACTERES_NOME = 40;

  private final ServicoAutenticacao autenticacao;
  private final RepositorioColecoesPerfil colecoes;
  private final RepositorioJogos jogos;

  ControladorColecoesPerfil(ServicoAutenticacao autenticacao, RepositorioColecoesPerfil colecoes, RepositorioJogos jogos) {
    this.autenticacao = autenticacao;
    this.colecoes = colecoes;
    this.jogos = jogos;
  }

  @GetMapping
  ResponseEntity<?> listar(@RequestHeader(value = "Authorization", required = false) String autorizacao) {
    Optional<String> usuarioId = autenticacao.buscarUsuarioPeloCabecalho(autorizacao);
    if (usuarioId.isEmpty()) return naoAutenticado();
    return ResponseEntity.ok(colecoes.listarPorUsuario(usuarioId.get()));
  }

  @PostMapping
  ResponseEntity<?> criar(@RequestHeader(value = "Authorization", required = false) String autorizacao, @RequestBody(required = false) RequisicaoColecao requisicao) {
    Optional<String> usuarioId = autenticacao.buscarUsuarioPeloCabecalho(autorizacao);
    if (usuarioId.isEmpty()) return naoAutenticado();
    String nome = normalizarNome(requisicao == null ? null : requisicao.nome());
    if (nome == null) return ResponseEntity.badRequest().body(Map.of("error", "Informe um nome de ate " + MAXIMO_CARACTERES_NOME + " caracteres"));
    if (colecoes.contarColecoes(usuarioId.get()) >= MAXIMO_COLECOES) {
      return ResponseEntity.badRequest().body(Map.of("error", "Limite de " + MAXIMO_COLECOES + " colecoes atingido"));
    }
    return ResponseEntity.status(201).body(Map.of("id", colecoes.criar(usuarioId.get(), nome)));
  }

  @PutMapping("/{colecaoId}")
  ResponseEntity<?> renomear(@RequestHeader(value = "Authorization", required = false) String autorizacao, @PathVariable long colecaoId, @RequestBody(required = false) RequisicaoColecao requisicao) {
    Optional<String> usuarioId = autenticacao.buscarUsuarioPeloCabecalho(autorizacao);
    if (usuarioId.isEmpty()) return naoAutenticado();
    String nome = normalizarNome(requisicao == null ? null : requisicao.nome());
    if (nome == null) return ResponseEntity.badRequest().body(Map.of("error", "Informe um nome de ate " + MAXIMO_CARACTERES_NOME + " caracteres"));
    if (!colecoes.renomear(usuarioId.get(), colecaoId, nome)) return colecaoNaoEncontrada();
    return ResponseEntity.ok(Map.of("ok", true));
  }

  @DeleteMapping("/{colecaoId}")
  ResponseEntity<?> excluir(@RequestHeader(value = "Authorization", required = false) String autorizacao, @PathVariable long colecaoId) {
    Optional<String> usuarioId = autenticacao.buscarUsuarioPeloCabecalho(autorizacao);
    if (usuarioId.isEmpty()) return naoAutenticado();
    if (!colecoes.excluir(usuarioId.get(), colecaoId)) return colecaoNaoEncontrada();
    return ResponseEntity.ok(Map.of("ok", true));
  }

  @PostMapping("/{colecaoId}/itens")
  ResponseEntity<?> adicionarItem(@RequestHeader(value = "Authorization", required = false) String autorizacao, @PathVariable long colecaoId, @RequestBody(required = false) RequisicaoItemColecao requisicao) {
    Optional<String> usuarioId = autenticacao.buscarUsuarioPeloCabecalho(autorizacao);
    if (usuarioId.isEmpty()) return naoAutenticado();
    if (!colecoes.pertenceAoUsuario(usuarioId.get(), colecaoId)) return colecaoNaoEncontrada();
    if (requisicao == null) return ResponseEntity.badRequest().body(Map.of("error", "Informe slug ou steamAppId"));
    if (colecoes.contarItens(colecaoId) >= MAXIMO_ITENS) {
      return ResponseEntity.badRequest().body(Map.of("error", "Limite de " + MAXIMO_ITENS + " jogos por colecao atingido"));
    }

    boolean temSlug = requisicao.slug() != null && !requisicao.slug().isBlank();
    boolean temAppId = requisicao.steamAppId() != null && requisicao.steamAppId() > 0;
    if (temSlug == temAppId) return ResponseEntity.badRequest().body(Map.of("error", "Informe slug ou steamAppId, nunca os dois"));

    if (temSlug) {
      Optional<Long> jogoId = jogos.buscarIdPorSlug(requisicao.slug());
      if (jogoId.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Jogo nao encontrado"));
      colecoes.adicionarJogo(usuarioId.get(), colecaoId, jogoId.get());
    } else {
      if (colecoes.tituloSteam(usuarioId.get(), requisicao.steamAppId()).isEmpty()) {
        return ResponseEntity.status(404).body(Map.of("error", "Jogo nao encontrado na biblioteca Steam"));
      }
      colecoes.adicionarSteam(usuarioId.get(), colecaoId, requisicao.steamAppId());
    }
    return ResponseEntity.status(201).body(Map.of("ok", true));
  }

  @DeleteMapping("/{colecaoId}/itens/jogo/{slug}")
  ResponseEntity<?> removerJogo(@RequestHeader(value = "Authorization", required = false) String autorizacao, @PathVariable long colecaoId, @PathVariable String slug) {
    Optional<String> usuarioId = autenticacao.buscarUsuarioPeloCabecalho(autorizacao);
    if (usuarioId.isEmpty()) return naoAutenticado();
    if (!colecoes.pertenceAoUsuario(usuarioId.get(), colecaoId)) return colecaoNaoEncontrada();
    Optional<Long> jogoId = jogos.buscarIdPorSlug(slug);
    if (jogoId.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Jogo nao encontrado"));
    colecoes.removerJogo(usuarioId.get(), colecaoId, jogoId.get());
    return ResponseEntity.ok(Map.of("ok", true));
  }

  @DeleteMapping("/{colecaoId}/itens/steam/{appId}")
  ResponseEntity<?> removerSteam(@RequestHeader(value = "Authorization", required = false) String autorizacao, @PathVariable long colecaoId, @PathVariable int appId) {
    Optional<String> usuarioId = autenticacao.buscarUsuarioPeloCabecalho(autorizacao);
    if (usuarioId.isEmpty()) return naoAutenticado();
    if (!colecoes.pertenceAoUsuario(usuarioId.get(), colecaoId)) return colecaoNaoEncontrada();
    colecoes.removerSteam(usuarioId.get(), colecaoId, appId);
    return ResponseEntity.ok(Map.of("ok", true));
  }

  // 404 tambem quando a colecao existe mas e de outro usuario: nao revela a existencia dela.
  private static ResponseEntity<?> colecaoNaoEncontrada() {
    return ResponseEntity.status(404).body(Map.of("error", "Colecao nao encontrada"));
  }

  private static ResponseEntity<?> naoAutenticado() {
    return ResponseEntity.status(401).body(Map.of("error", "Nao autenticado"));
  }

  private static String normalizarNome(String valor) {
    String nome = valor == null ? "" : valor.trim();
    return nome.isEmpty() || nome.length() > MAXIMO_CARACTERES_NOME ? null : nome;
  }
}
