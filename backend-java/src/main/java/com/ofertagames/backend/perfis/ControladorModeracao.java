package com.ofertagames.backend.perfis;

import com.ofertagames.backend.autenticacao.Administradores;
import com.ofertagames.backend.autenticacao.ServicoAutenticacao;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Moderacao de perfis publicos (issue #25): denuncia por usuario logado e, pro admin, lista de
 * denuncias abertas e bloqueio de perfil.
 *
 * <p>Denuncia exige login de proposito: anonima viraria canal de spam sem nenhum freio alem do IP.
 */
@RestController
public class ControladorModeracao {
  private static final Logger logger = LoggerFactory.getLogger(ControladorModeracao.class);

  /** Por pessoa, por dia. Folgado pra uso real e baixo o bastante pra nao virar ferramenta de assedio. */
  static final int DENUNCIAS_POR_DIA = 10;

  private final ServicoAutenticacao autenticacao;
  private final Administradores administradores;
  private final RepositorioPerfis perfis;
  private final RepositorioModeracao moderacao;

  ControladorModeracao(ServicoAutenticacao autenticacao, Administradores administradores,
      RepositorioPerfis perfis, RepositorioModeracao moderacao) {
    this.autenticacao = autenticacao;
    this.administradores = administradores;
    this.perfis = perfis;
    this.moderacao = moderacao;
  }

  @PostMapping("/api/perfis/{handle}/denunciar")
  ResponseEntity<Void> denunciar(@PathVariable String handle,
      @RequestHeader(value = "Authorization", required = false) String autorizacao,
      @RequestBody EntradaDenuncia entrada) {
    String denunciante = autenticacao.buscarUsuarioPeloCabecalho(autorizacao)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Entre na sua conta para denunciar"));
    String motivo = entrada == null || entrada.motivo() == null ? "" : entrada.motivo().trim();
    if (motivo.length() < 3 || motivo.length() > 500) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Descreva o motivo em 3 a 500 caracteres");
    }
    RepositorioPerfis.Perfil perfil = perfis.buscarPorHandle(handle.trim().toLowerCase(Locale.ROOT))
        .filter(p -> p.publico() && !p.bloqueado())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (perfil.usuarioId().equals(denunciante)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Você não pode denunciar o próprio perfil");
    }
    if (moderacao.jaDenunciouEmAberto(perfil.usuarioId(), denunciante)) {
      // Idempotente pra quem denuncia: a denuncia anterior ainda esta na fila.
      return ResponseEntity.accepted().build();
    }
    if (moderacao.contarDoDenuncianteNasUltimas24h(denunciante) >= DENUNCIAS_POR_DIA) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Você atingiu o limite de denúncias por hoje");
    }
    moderacao.registrar(perfil.usuarioId(), denunciante, motivo);
    logger.info("Denuncia registrada contra o perfil {}", perfil.handle());
    return ResponseEntity.accepted().build();
  }

  @GetMapping("/api/admin/denuncias")
  List<RepositorioModeracao.DenunciaAberta> listar(@RequestHeader(value = "Authorization", required = false) String autorizacao) {
    administradores.exigir(autorizacao);
    return moderacao.listarAbertas();
  }

  @PostMapping("/api/admin/denuncias/{id}/resolver")
  ResponseEntity<Void> resolver(@PathVariable long id, @RequestHeader(value = "Authorization", required = false) String autorizacao) {
    administradores.exigir(autorizacao);
    moderacao.resolver(id);
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/api/admin/perfis/{handle}/bloqueio")
  ResponseEntity<Void> bloquear(@PathVariable String handle,
      @RequestHeader(value = "Authorization", required = false) String autorizacao,
      @RequestBody EntradaBloqueio entrada) {
    String admin = administradores.exigir(autorizacao);
    boolean bloquear = entrada != null && entrada.bloqueado();
    if (perfis.definirBloqueio(handle.trim().toLowerCase(Locale.ROOT), bloquear) == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Perfil não encontrado");
    }
    logger.warn("Perfil {} {} pelo admin {}", handle, bloquear ? "BLOQUEADO" : "desbloqueado", admin);
    return ResponseEntity.noContent().build();
  }

  record EntradaDenuncia(String motivo) {}
  record EntradaBloqueio(boolean bloqueado) {}
}
