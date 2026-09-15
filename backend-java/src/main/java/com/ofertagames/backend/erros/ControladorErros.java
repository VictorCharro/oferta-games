package com.ofertagames.backend.erros;

import com.ofertagames.backend.autenticacao.Administradores;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ControladorErros {
  private final RegistroErros registro;
  private final Administradores administradores;

  ControladorErros(RegistroErros registro, Administradores administradores) {
    this.registro = registro;
    this.administradores = administradores;
  }

  /**
   * Relato de erro do navegador (ErrorHandler do Angular). Anonimo e sempre 202: quem manda nao faz
   * nada com a resposta, e recusar com erro so geraria outro erro no console.
   */
  @PostMapping("/api/erros")
  ResponseEntity<Void> relatar(@RequestBody(required = false) RelatoErro relato,
      @RequestHeader(value = "User-Agent", required = false) String userAgent) {
    if (relato != null && relato.mensagem() != null && registro.aceitarRelatoNavegador()) {
      registro.registrar("navegador", relato.mensagem(), relato.pilha(), relato.pagina(), userAgent);
    }
    return ResponseEntity.accepted().build();
  }

  @GetMapping("/api/admin/erros")
  List<RegistroErros.ErroRegistrado> listar(@RequestHeader(value = "Authorization", required = false) String autorizacao,
      @RequestParam(defaultValue = "false") boolean resolvidos) {
    administradores.exigir(autorizacao);
    return registro.listar(resolvidos);
  }

  @PostMapping("/api/admin/erros/{id}/resolver")
  ResponseEntity<Void> resolver(@PathVariable long id, @RequestHeader(value = "Authorization", required = false) String autorizacao) {
    administradores.exigir(autorizacao);
    registro.resolver(id);
    return ResponseEntity.noContent().build();
  }

  record RelatoErro(String mensagem, String pilha, String pagina) {}
}
