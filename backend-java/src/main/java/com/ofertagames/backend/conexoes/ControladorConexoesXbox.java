package com.ofertagames.backend.conexoes;

import com.ofertagames.backend.autenticacao.ServicoAutenticacao;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/conexoes/xbox")
public class ControladorConexoesXbox {
  private final ServicoAutenticacao autenticacao;
  private final ServicoConexoesXbox xbox;
  private final TaskExecutor executor;

  ControladorConexoesXbox(ServicoAutenticacao autenticacao, ServicoConexoesXbox xbox,
      @Qualifier("executorColetaManual") TaskExecutor executor) {
    this.autenticacao = autenticacao;
    this.xbox = xbox;
    this.executor = executor;
  }

  // O redirect pra Microsoft acontece inteiro no navegador (api.xbl.io/app/auth/{app_key}), por
  // isso o frontend so precisa da app_key pra montar essa URL - nao ha "state" server-side
  // porque a correlacao com o usuario acontece depois, no /concluir, via o bearer token normal
  // (o usuario continua logado no nosso site durante todo o redirect, sessao fica no localStorage).
  // A app_key do OpenXBL e explicitamente publica ("Public Key" no painel deles), entao esse
  // endpoint nao exige autenticacao.
  @GetMapping("/app-key")
  Map<String, String> appKey() {
    return Map.of("appKey", xbox.appKey());
  }

  @PostMapping("/concluir")
  ResponseEntity<Void> concluir(@RequestHeader(value = "Authorization", required = false) String autorizacao,
      @RequestBody(required = false) RequisicaoConcluirXbox requisicao) {
    if (requisicao == null || requisicao.code() == null || requisicao.code().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Codigo do Xbox ausente");
    }
    xbox.concluir(usuario(autorizacao), requisicao.code());
    return ResponseEntity.ok().build();
  }

  @GetMapping
  ServicoConexoesXbox.StatusConexaoXbox status(@RequestHeader(value = "Authorization", required = false) String autorizacao) {
    return xbox.status(usuario(autorizacao));
  }

  @PostMapping("/sincronizar")
  ResponseEntity<Void> sincronizar(@RequestHeader(value = "Authorization", required = false) String autorizacao) {
    String usuarioId = usuario(autorizacao);
    executor.execute(() -> xbox.sincronizarBiblioteca(usuarioId));
    return ResponseEntity.accepted().build();
  }

  @DeleteMapping
  ResponseEntity<Void> remover(@RequestHeader(value = "Authorization", required = false) String autorizacao) {
    xbox.remover(usuario(autorizacao));
    return ResponseEntity.noContent().build();
  }

  private String usuario(String autorizacao) {
    return autenticacao.buscarUsuarioPeloCabecalho(autorizacao)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
  }

  record RequisicaoConcluirXbox(String code) {}
}
