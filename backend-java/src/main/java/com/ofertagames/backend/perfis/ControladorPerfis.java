package com.ofertagames.backend.perfis;

import com.ofertagames.backend.autenticacao.ServicoAutenticacao;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/perfis")
public class ControladorPerfis {
  private final ServicoAutenticacao autenticacao;
  private final ServicoPerfis perfis;

  ControladorPerfis(ServicoAutenticacao autenticacao, ServicoPerfis perfis) { this.autenticacao = autenticacao; this.perfis = perfis; }

  @GetMapping("/me")
  RepositorioPerfis.Perfil proprio(@RequestHeader(value = "Authorization", required = false) String autorizacao) { return perfis.proprio(usuario(autorizacao)); }

  @PutMapping("/me")
  void salvar(@RequestHeader(value = "Authorization", required = false) String autorizacao, @RequestBody ServicoPerfis.EntradaPerfil entrada) { perfis.salvar(usuario(autorizacao), entrada); }

  @GetMapping("/{handle}")
  ServicoPerfis.PerfilPublico publico(@PathVariable String handle) { return perfis.publico(handle); }

  private String usuario(String autorizacao) { return autenticacao.buscarUsuarioPeloCabecalho(autorizacao).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)); }
}
