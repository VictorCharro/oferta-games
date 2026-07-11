package com.ofertagames.backend.sincronizacao;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class EstadoColeta {
  private final Map<String, RegistroColeta> registros = new LinkedHashMap<>();

  synchronized void iniciar(String tipo) {
    RegistroColeta anterior = registros.get(tipo);
    registros.put(tipo, new RegistroColeta(
        tipo,
        true,
        Instant.now(),
        anterior == null ? null : anterior.ultimaConclusao(),
        anterior == null ? null : anterior.ultimaDuracaoMs(),
        anterior == null ? 0 : anterior.jogosAtualizados(),
        anterior == null ? 0 : anterior.ofertasAtualizadas(),
        anterior == null ? null : anterior.ultimoErro()));
  }

  synchronized void concluir(String tipo, ResultadoRodadaColeta resultado, long duracaoMs) {
    registros.put(tipo, new RegistroColeta(
        tipo,
        false,
        null,
        Instant.now(),
        duracaoMs,
        resultado.jogosAtualizados(),
        resultado.ofertasAtualizadas(),
        null));
  }

  synchronized void falhar(String tipo, RuntimeException erro, long duracaoMs) {
    RegistroColeta anterior = registros.get(tipo);
    registros.put(tipo, new RegistroColeta(
        tipo,
        false,
        null,
        anterior == null ? null : anterior.ultimaConclusao(),
        duracaoMs,
        anterior == null ? 0 : anterior.jogosAtualizados(),
        anterior == null ? 0 : anterior.ofertasAtualizadas(),
        resumirErro(erro)));
  }

  synchronized RegistroColeta consultar(String tipo) {
    return registros.getOrDefault(tipo, RegistroColeta.vazio(tipo));
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
