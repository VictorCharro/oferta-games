package com.ofertagames.backend.conexoes;

import com.ofertagames.backend.autenticacao.ServicoAutenticacao;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/conexoes/steam")
public class ControladorConexoesSteam {
  private final ServicoAutenticacao autenticacao;
  private final ServicoConexoesSteam steam;
  private final TaskExecutor executor;

  ControladorConexoesSteam(ServicoAutenticacao autenticacao, ServicoConexoesSteam steam,
      @Qualifier("executorColetaManual") TaskExecutor executor) {
    this.autenticacao = autenticacao;
    this.steam = steam;
    this.executor = executor;
  }

  @PostMapping("/iniciar")
  Map<String, String> iniciar(@RequestHeader(value = "Authorization", required = false) String autorizacao) {
    return Map.of("url", steam.iniciar(usuario(autorizacao)));
  }

  @GetMapping("/retorno")
  ResponseEntity<Void> retorno(@RequestParam UUID state, @RequestParam MultiValueMap<String, String> parametros) {
    Optional<String> usuario = steam.concluir(state, parametros);
    if (usuario.isPresent()) {
      executor.execute(() -> steam.sincronizarBiblioteca(usuario.get()));
      return ResponseEntity.status(HttpStatus.FOUND)
          .header(HttpHeaders.LOCATION, steam.urlRetornoSucesso())
          .build();
    }
    return ResponseEntity.status(HttpStatus.FOUND)
        .header(HttpHeaders.LOCATION, steam.urlRetornoErro())
        .build();
  }

  @GetMapping
  ServicoConexoesSteam.StatusConexaoSteam status(@RequestHeader(value = "Authorization", required = false) String autorizacao) {
    return steam.status(usuario(autorizacao));
  }

  // O dono precisa da biblioteca inteira (ex: montar colecoes), nao so da previa do perfil publico.
  @GetMapping("/biblioteca")
  java.util.List<ServicoConexoesSteam.JogoBibliotecaSteam> biblioteca(
      @RequestHeader(value = "Authorization", required = false) String autorizacao) {
    return steam.biblioteca(usuario(autorizacao), 2000);
  }

  @PutMapping("/platinados/ordem")
  ResponseEntity<Void> ordenarPlatinados(@RequestHeader(value = "Authorization", required = false) String autorizacao,
      @RequestBody(required = false) RequisicaoOrdemPlatinados requisicao) {
    steam.reordenarPlatinados(usuario(autorizacao), requisicao == null ? java.util.List.of() : requisicao.appIds());
    return ResponseEntity.ok().build();
  }

  @PostMapping("/sincronizar")
  ResponseEntity<Void> sincronizar(@RequestHeader(value = "Authorization", required = false) String autorizacao) {
    String usuario = usuario(autorizacao);
    executor.execute(() -> {
      steam.sincronizarBiblioteca(usuario);
      steam.sincronizarConquistasDoUsuario(usuario, 50);
    });
    return ResponseEntity.accepted().build();
  }

  @DeleteMapping
  ResponseEntity<Void> remover(@RequestHeader(value = "Authorization", required = false) String autorizacao) {
    steam.remover(usuario(autorizacao));
    return ResponseEntity.noContent().build();
  }

  private String usuario(String autorizacao) {
    return autenticacao.buscarUsuarioPeloCabecalho(autorizacao)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
  }
}
