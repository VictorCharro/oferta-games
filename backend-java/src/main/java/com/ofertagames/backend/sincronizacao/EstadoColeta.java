package com.ofertagames.backend.sincronizacao;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Status e contadores de cada tipo de coleta, mostrados em {@code /admin/coleta}.
 *
 * <p>Persistido na tabela {@code coleta_status}, e nao num {@code Map} em memoria: o backend
 * redeploya varias vezes ao dia (todo push que toca {@code backend-java/}), e um Map perderia
 * status e contadores a cada deploy, fazendo a tela de admin parecer resetada o tempo todo.
 */
@Component
public class EstadoColeta {
  private final JdbcClient jdbc;

  EstadoColeta(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * No boot, marca como nao-executando qualquer job que ficou preso em "em execucao".
   *
   * <p>Logo apos o processo subir nenhum job pode estar de fato rodando, entao uma linha nesse
   * estado so pode ter vindo de um restart no meio de uma rodada (deploy, crash, OOM). Sem essa
   * limpeza a tela de admin mostraria "Em execucao" ate o proximo disparo daquele mesmo tipo.
   *
   * <p>Nao mexe na trava de {@code sync_locks} — essa expira sozinha por tempo.
   */
  @PostConstruct
  void limparExecucoesTravadas() {
    jdbc.sql("UPDATE coleta_status SET em_execucao = false WHERE em_execucao = true").update();
  }

  void iniciar(String tipo) {
    jdbc.sql("""
        INSERT INTO coleta_status (tipo, em_execucao, inicio_atual)
        VALUES (:tipo, true, now())
        ON CONFLICT (tipo) DO UPDATE
          SET em_execucao = true, inicio_atual = now()
        """)
        .param("tipo", tipo)
        .update();
  }

  void concluir(String tipo, ResultadoRodadaColeta resultado, long duracaoMs) {
    jdbc.sql("""
        INSERT INTO coleta_status (tipo, em_execucao, inicio_atual, ultima_conclusao, ultima_duracao_ms, jogos_atualizados, ofertas_atualizadas, ultimo_erro)
        VALUES (:tipo, false, NULL, now(), :duracaoMs, :jogosAtualizados, :ofertasAtualizadas, NULL)
        ON CONFLICT (tipo) DO UPDATE
          SET em_execucao = false,
              inicio_atual = NULL,
              ultima_conclusao = now(),
              ultima_duracao_ms = EXCLUDED.ultima_duracao_ms,
              jogos_atualizados = EXCLUDED.jogos_atualizados,
              ofertas_atualizadas = EXCLUDED.ofertas_atualizadas,
              ultimo_erro = NULL
        """)
        .param("tipo", tipo)
        .param("duracaoMs", duracaoMs)
        .param("jogosAtualizados", resultado.jogosAtualizados())
        .param("ofertasAtualizadas", resultado.ofertasAtualizadas())
        .update();
  }

  void falhar(String tipo, RuntimeException erro, long duracaoMs) {
    String resumo = resumirErro(erro);
    jdbc.sql("""
        INSERT INTO coleta_status (tipo, em_execucao, inicio_atual, ultima_duracao_ms, ultimo_erro)
        VALUES (:tipo, false, NULL, :duracaoMs, :erro)
        ON CONFLICT (tipo) DO UPDATE
          SET em_execucao = false,
              inicio_atual = NULL,
              ultima_duracao_ms = EXCLUDED.ultima_duracao_ms,
              ultimo_erro = EXCLUDED.ultimo_erro
        """)
        .param("tipo", tipo)
        .param("duracaoMs", duracaoMs)
        .param("erro", resumo)
        .update();
  }

  RegistroColeta consultar(String tipo) {
    return jdbc.sql("""
        SELECT tipo, em_execucao, inicio_atual, ultima_conclusao, ultima_duracao_ms,
               jogos_atualizados, ofertas_atualizadas, ultimo_erro
        FROM coleta_status
        WHERE tipo = :tipo
        """)
        .param("tipo", tipo)
        .query((rs, linha) -> new RegistroColeta(
            rs.getString("tipo"),
            rs.getBoolean("em_execucao"),
            instanteOuNulo(rs.getTimestamp("inicio_atual")),
            instanteOuNulo(rs.getTimestamp("ultima_conclusao")),
            (Long) rs.getObject("ultima_duracao_ms"),
            rs.getInt("jogos_atualizados"),
            rs.getInt("ofertas_atualizadas"),
            rs.getString("ultimo_erro")))
        .optional()
        .orElseGet(() -> RegistroColeta.vazio(tipo));
  }

  private static Instant instanteOuNulo(java.sql.Timestamp valor) {
    return valor == null ? null : valor.toInstant();
  }

  private static String resumirErro(RuntimeException erro) {
    String mensagem = erro.getMessage();
    return erro.getClass().getSimpleName() + (mensagem == null || mensagem.isBlank() ? "" : ": " + mensagem);
  }

  public record RegistroColeta(
      String tipo,
      boolean emExecucao,
      Instant inicioAtual,
      Instant ultimaConclusao,
      Long ultimaDuracaoMs,
      int jogosAtualizados,
      int ofertasAtualizadas,
      String ultimoErro
  ) {
    static RegistroColeta vazio(String tipo) {
      return new RegistroColeta(tipo, false, null, null, null, 0, 0, null);
    }

    public Long duracaoAtualMs() {
      return inicioAtual == null ? null : Duration.between(inicioAtual, Instant.now()).toMillis();
    }
  }
}
