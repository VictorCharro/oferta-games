package com.ofertagames.backend.administracao;

import com.ofertagames.backend.autenticacao.Administradores;
import com.ofertagames.backend.instantgaming.ServicoInstantGaming;
import com.ofertagames.backend.jogos.RepositorioJogos;
import com.ofertagames.backend.jogos.ServicoCatalogo;
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
  private final Administradores administradores;
  private final ServicoExecucaoColeta execucao;
  private final ServicoSincronizacao sincronizacao;
  private final RepositorioJogos jogos;
  private final ServicoCatalogo catalogo;
  private final ServicoInstantGaming instantGaming;
  private final TaskExecutor executorManual;

  ControladorAdministracao(
      Administradores administradores,
      ServicoExecucaoColeta execucao,
      ServicoSincronizacao sincronizacao,
      RepositorioJogos jogos,
      ServicoCatalogo catalogo,
      ServicoInstantGaming instantGaming,
      @Qualifier("executorColetaManual") TaskExecutor executorManual
  ) {
    this.administradores = administradores;
    this.execucao = execucao;
    this.sincronizacao = sincronizacao;
    this.jogos = jogos;
    this.catalogo = catalogo;
    this.instantGaming = instantGaming;
    this.executorManual = executorManual;
  }

  @GetMapping("/coleta")
  StatusAdministrativoColeta consultar(@RequestHeader(value = "Authorization", required = false) String autorizacao) {
    exigirAdministrador(autorizacao);
    return new StatusAdministrativoColeta(
        mapearStatus(execucao.consultar("precos")),
        mapearStatus(execucao.consultar("steam")),
        mapearStatus(execucao.consultar("detalhes")),
        mapearStatus(execucao.consultar("conquistas-catalogo")),
        mapearStatus(execucao.consultar("instant-gaming-escaneamento")),
        mapearStatus(execucao.consultar("instant-gaming-casamento")),
        mapearStatus(execucao.consultar("instant-gaming-precos")),
        jogos.resumirFilaColeta(),
        jogos.contarPendentesDetalhes(),
        jogos.contarPendentesConquistas(),
        instantGaming.resumirFila());
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
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de coleta inválido");
    };
    executorManual.execute(tarefa);
    return ResponseEntity.accepted().body(new RespostaDisparoColeta(true, tipo));
  }

  // Preenche metadados Steam, detalhes e conquistas de UM jogo na hora (sincrono), pro admin nao
  // precisar esperar ele chegar na vez na fila normal quando um jogo novo/pouco tocado esta bombando.
  @PostMapping("/jogos/{slug}/preencher-tudo")
  ServicoCatalogo.ResultadoPreenchimentoJogo preencherJogo(
      @RequestHeader(value = "Authorization", required = false) String autorizacao,
      @PathVariable String slug
  ) {
    exigirAdministrador(autorizacao);
    try {
      return catalogo.preencherTudoDoJogo(slug);
    } catch (ServicoCatalogo.JogoNaoEncontradoException erro) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Jogo não encontrado");
    }
  }

  private void exigirAdministrador(String autorizacao) {
    administradores.exigir(autorizacao);
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
      StatusColetaAdministrativa detalhes,
      StatusColetaAdministrativa conquistasCatalogo,
      StatusColetaAdministrativa instantGamingEscaneamento,
      StatusColetaAdministrativa instantGamingCasamento,
      StatusColetaAdministrativa instantGamingPrecos,
      RepositorioJogos.ResumoFilaColeta fila,
      long pendentesDetalhes,
      long pendentesConquistas,
      ServicoInstantGaming.ResumoFilaInstantGaming filaInstantGaming
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
