package com.ofertagames.backend.administracao;

import com.ofertagames.backend.autenticacao.ServicoAutenticacao;
import com.ofertagames.backend.jogos.RepositorioJogos;
import com.ofertagames.backend.sincronizacao.EstadoColeta.RegistroColeta;
import com.ofertagames.backend.sincronizacao.ServicoExecucaoColeta;
import com.ofertagames.backend.sincronizacao.ServicoSincronizacao;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin")
public class ControladorAdministracao {
  private static final String UID_ADMINISTRADOR = "0a6eb06b-756e-4434-899b-33420bed8609";

  private final ServicoAutenticacao autenticacao;
  private final ServicoExecucaoColeta execucao;
  private final ServicoSincronizacao sincronizacao;
  private final RepositorioJogos jogos;
  private final TaskExecutor executorManual;

  ControladorAdministracao(
      ServicoAutenticacao autenticacao,
      ServicoExecucaoColeta execucao,
      ServicoSincronizacao sincronizacao,
      RepositorioJogos jogos,
      @Qualifier("executorColetaManual") TaskExecutor executorManual
  ) {
    this.autenticacao = autenticacao;
    this.execucao = execucao;
    this.sincronizacao = sincronizacao;
    this.jogos = jogos;
    this.executorManual = executorManual;
  }

  @GetMapping("/coleta")
  StatusAdministrativoColeta consultar(@RequestHeader(value = "Authorization", required = false) String autorizacao) {
    exigirAdministrador(autorizacao);
    return new StatusAdministrativoColeta(
        mapearStatus(execucao.consultar("precos")),
        mapearStatus(execucao.consultar("steam")),
        jogos.resumirFilaColeta());
  }

  @PostMapping("/coleta/{tipo}")
  ResponseEntity<RespostaDisparoColeta> disparar(
      @RequestHeader(value = "Authorization", required = false) String autorizacao,
      @PathVariable String tipo
  ) {
    exigirAdministrador(autorizacao);
    Runnable tarefa = switch (tipo) {
      case "precos" -> () -> execucao.executar("precos", sincronizacao::sincronizarRodadaPrecos);
      case "steam" -> () -> execucao.executar("steam", sincronizacao::sincronizarRodadaSteam);
      case "detalhes" -> () -> execucao.executar("detalhes", sincronizacao::sincronizarRodadaDetalhes);
      case "conquistas-catalogo" -> () -> execucao.executar("conquistas-catalogo", sincronizacao::sincronizarRodadaConquistasCatalogo);
      case "instant-gaming-escaneamento" -> () -> execucao.executar("instant-gaming-escaneamento", sincronizacao::sincronizarRodadaInstantGamingEscaneamento);
      case "instant-gaming-casamento" -> () -> execucao.executar("instant-gaming-casamento", sincronizacao::sincronizarRodadaInstantGamingCasamento);
      case "instant-gaming-precos" -> () -> execucao.executar("instant-gaming-precos", sincronizacao::sincronizarRodadaInstantGamingPrecos);
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de coleta invalido");
    };
    executorManual.execute(tarefa);
    return ResponseEntity.accepted().body(new RespostaDisparoColeta(true, tipo));
  }

  private void exigirAdministrador(String autorizacao) {
    String usuarioId = autenticacao.buscarUsuarioPeloCabecalho(autorizacao)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    if (!UID_ADMINISTRADOR.equals(usuarioId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
  }

  private static StatusColetaAdministrativa mapearStatus(RegistroColeta status) {
    return new StatusColetaAdministrativa(
        status.tipo(),
        status.emExecucao(),
        status.inicioAtual(),
        status.duracaoAtualMs(),
        status.ultimaConclusao(),
        status.ultimaDuracaoMs(),
        status.jogosAtualizados(),
        status.ofertasAtualizadas(),
        status.ultimoErro());
  }

  public record StatusAdministrativoColeta(
      StatusColetaAdministrativa precos,
      StatusColetaAdministrativa steam,
      RepositorioJogos.ResumoFilaColeta fila
  ) {}

  public record StatusColetaAdministrativa(
      String tipo,
      boolean emExecucao,
      Instant inicioAtual,
      Long duracaoAtualMs,
      Instant ultimaConclusao,
      Long ultimaDuracaoMs,
      int jogosAtualizados,
      int ofertasAtualizadas,
      String ultimoErro
  ) {}

  public record RespostaDisparoColeta(boolean aceita, String tipo) {}
}
