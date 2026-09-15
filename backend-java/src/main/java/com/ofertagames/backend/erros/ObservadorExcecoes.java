package com.ofertagames.backend.erros;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.server.ResponseStatusException;

/**
 * Registra em app_errors as excecoes que viram 500 no backend (issue #29).
 *
 * <p>So OBSERVA: devolve {@code null}, entao o tratamento segue igual (TratadorErrosApi e o
 * padrao do Spring). Fica fora o que nao e bug: erros com status definido de proposito
 * (ResponseStatusException e as excecoes do Spring MVC com status, como 404 de rota e 400 de
 * parametro) e cliente que fechou a conexao no meio da resposta.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ObservadorExcecoes implements HandlerExceptionResolver {
  private final RegistroErros registro;

  ObservadorExcecoes(RegistroErros registro) {
    this.registro = registro;
  }

  @Override
  public ModelAndView resolveException(HttpServletRequest requisicao, HttpServletResponse resposta, Object handler, Exception erro) {
    if (deveRegistrar(erro)) {
      StringWriter pilha = new StringWriter();
      erro.printStackTrace(new PrintWriter(pilha));
      registro.registrar("servidor", erro.getClass().getName() + (erro.getMessage() == null ? "" : ": " + erro.getMessage()),
          pilhaDoProjeto(pilha.toString()), requisicao.getMethod() + " " + requisicao.getRequestURI(), null);
    }
    return null;
  }

  static boolean deveRegistrar(Exception erro) {
    if (erro instanceof ResponseStatusException status) return status.getStatusCode().is5xxServerError();
    if (erro instanceof ErrorResponse resposta) return resposta.getStatusCode().is5xxServerError();
    if (erro instanceof AsyncRequestNotUsableException) return false;
    String nome = erro.getClass().getSimpleName();
    return !nome.equals("ClientAbortException") && !nome.equals("MaxUploadSizeExceededException");
  }

  /** Primeira linha e as linhas do nosso codigo: a pilha inteira do Spring estoura os 4000 chars sem dizer nada. */
  static String pilhaDoProjeto(String pilha) {
    StringBuilder resultado = new StringBuilder();
    pilha.lines().forEach(linha -> {
      if (resultado.isEmpty() || linha.contains("com.ofertagames") || linha.startsWith("Caused by")) {
        resultado.append(linha.strip()).append('\n');
      }
    });
    return resultado.toString();
  }
}
