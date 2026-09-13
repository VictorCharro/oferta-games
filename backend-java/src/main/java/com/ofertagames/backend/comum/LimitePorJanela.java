package com.ofertagames.backend.comum;

import java.time.Clock;
import java.time.Duration;

/**
 * Teto GLOBAL de uso numa janela fixa (ex.: 200 chamadas a ITAD por hora), independente de IP.
 *
 * <p>O {@code FiltroLimiteRequisicoes} limita por IP, o que nao segura quem distribui as
 * requisicoes entre muitos IPs — e as rotas anonimas que gastam cota externa (ITAD, Steam) pagam
 * por chamada, nao por visitante (issue #24). Este teto e o freio de ultimo caso: acima dele a
 * operacao cara e recusada ate a janela virar, e o resto do site segue funcionando.
 *
 * <p>Janela fixa (e nao deslizante) de proposito: e um contador e um instante, sem guardar
 * historico, e o pior caso — o dobro do teto na virada da janela — continua limitado.
 */
public final class LimitePorJanela {
  private final int teto;
  private final long janelaMillis;
  private final Clock relogio;
  private long inicioJanela;
  private int usados;

  public LimitePorJanela(int teto, Duration janela) {
    this(teto, janela, Clock.systemUTC());
  }

  LimitePorJanela(int teto, Duration janela, Clock relogio) {
    this.teto = teto;
    this.janelaMillis = janela.toMillis();
    this.relogio = relogio;
    this.inicioJanela = relogio.millis();
  }

  /** Consome uma unidade se houver; {@code false} quando o teto da janela atual ja foi atingido. */
  public synchronized boolean tentarConsumir() {
    long agora = relogio.millis();
    if (agora - inicioJanela >= janelaMillis) {
      inicioJanela = agora;
      usados = 0;
    }
    if (usados >= teto) return false;
    usados++;
    return true;
  }

  /** Segundos ate a janela atual virar — vira {@code Retry-After}. */
  public synchronized long segundosAteLiberar() {
    long restante = janelaMillis - (relogio.millis() - inicioJanela);
    return Math.max(1, (restante + 999) / 1000);
  }
}
