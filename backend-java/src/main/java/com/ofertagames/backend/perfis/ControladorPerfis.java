package com.ofertagames.backend.perfis;

import com.ofertagames.backend.autenticacao.ServicoAutenticacao;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.bind.annotation.PostMapping;
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
  private final com.ofertagames.backend.conexoes.ServicoConexoesSteam steam;
  private final TaskExecutor executor;

  ControladorPerfis(ServicoAutenticacao autenticacao, ServicoPerfis perfis,
      com.ofertagames.backend.conexoes.ServicoConexoesSteam steam,
      @Qualifier("executorColetaManual") TaskExecutor executor) {
    this.autenticacao = autenticacao;
    this.perfis = perfis;
    this.steam = steam;
    this.executor = executor;
  }

  @GetMapping("/me")
  RepositorioPerfis.Perfil proprio(@RequestHeader(value = "Authorization", required = false) String autorizacao) { return perfis.proprio(usuario(autorizacao)); }

  @PutMapping("/me")
  void salvar(@RequestHeader(value = "Authorization", required = false) String autorizacao, @RequestBody ServicoPerfis.EntradaPerfil entrada) { perfis.salvar(usuario(autorizacao), entrada); }

  @PutMapping("/me/avatar")
  void atualizarAvatar(@RequestHeader(value = "Authorization", required = false) String autorizacao, @RequestBody ServicoPerfis.EntradaAvatar entrada) { perfis.atualizarAvatar(usuario(autorizacao), entrada); }

  @PutMapping("/me/banner")
  void atualizarBanner(@RequestHeader(value = "Authorization", required = false) String autorizacao, @RequestBody ServicoPerfis.EntradaBanner entrada) { perfis.atualizarBanner(usuario(autorizacao), entrada); }

  @PutMapping("/me/wishlist-steam")
  void atualizarMostrarWishlistSteam(@RequestHeader(value = "Authorization", required = false) String autorizacao, @RequestBody EntradaMostrarWishlistSteam entrada) {
    perfis.atualizarMostrarWishlistSteam(usuario(autorizacao), entrada.mostrar());
  }

  record EntradaMostrarWishlistSteam(boolean mostrar) {}

  @GetMapping("/me/blocos")
  java.util.List<RepositorioBlocosPerfil.BlocoPerfil> blocos(@RequestHeader(value = "Authorization", required = false) String autorizacao) { return perfis.blocos(usuario(autorizacao)); }

  @PutMapping("/me/blocos")
  void salvarBlocos(@RequestHeader(value = "Authorization", required = false) String autorizacao, @RequestBody java.util.List<RepositorioBlocosPerfil.BlocoPerfil> blocos) { perfis.salvarBlocos(usuario(autorizacao), blocos); }

  @GetMapping("/{handle}")
  ServicoPerfis.PerfilPublico publico(@PathVariable String handle, @RequestHeader(value = "Authorization", required = false) String autorizacao) {
    return perfis.publico(handle, autenticacao.buscarUsuarioPeloCabecalho(autorizacao).orElse(null));
  }

  @PostMapping("/{handle}/atualizar")
  ResponseEntity<ResultadoAtualizacao> atualizar(@PathVariable String handle,
      @RequestHeader(value = "Authorization", required = false) String autorizacao) {
    ServicoPerfis.ResultadoAtualizacao resultado = perfis.solicitarAtualizacao(handle,
        autenticacao.buscarUsuarioPeloCabecalho(autorizacao).orElse(null));
    if ("agendada".equals(resultado.status())) {
      executor.execute(() -> steam.sincronizarPerfilCompleto(resultado.usuarioId()));
    }
    return ResponseEntity.accepted().body(new ResultadoAtualizacao(resultado.status()));
  }

  record ResultadoAtualizacao(String status) {}

  private String usuario(String autorizacao) { return autenticacao.buscarUsuarioPeloCabecalho(autorizacao).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)); }
}
