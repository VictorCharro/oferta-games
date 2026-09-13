package com.ofertagames.backend.conta;

import com.ofertagames.backend.autenticacao.ServicoAutenticacao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/conta")
public class ControladorConta {
  private static final Logger logger = LoggerFactory.getLogger(ControladorConta.class);

  private final ServicoAutenticacao autenticacao;
  private final RepositorioConta conta;

  ControladorConta(ServicoAutenticacao autenticacao, RepositorioConta conta) {
    this.autenticacao = autenticacao;
    this.conta = conta;
  }

  /**
   * Apaga a conta de quem esta autenticado — so a propria, nunca por id vindo da requisicao.
   * A confirmacao ("digite EXCLUIR") fica no frontend; aqui basta a sessao valida.
   */
  @DeleteMapping
  ResponseEntity<Void> excluir(@RequestHeader(value = "Authorization", required = false) String autorizacao) {
    String usuario = autenticacao.buscarUsuarioPeloCabecalho(autorizacao)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    conta.excluir(usuario);
    // So o id, sem e-mail: o log nao pode guardar dado pessoal de quem pediu exclusao.
    logger.info("Conta excluida a pedido do titular: {}", usuario);
    return ResponseEntity.noContent().build();
  }
}
