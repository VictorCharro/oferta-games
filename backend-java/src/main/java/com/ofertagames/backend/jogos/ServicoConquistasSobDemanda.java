package com.ofertagames.backend.jogos;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * Coleta conquistas de um jogo quando alguem abre a pagina dele.
 *
 * <p><b>Por que existe.</b> O job que varria os 39 mil jogos com {@code steam_app_id} foi desligado:
 * a tabela de conquistas ja ocupava 121 MB com 35% da fila processada, e completar levaria o banco
 * a ~590 MB — acima da cota de 0,5 GB do plano free do Supabase. Guardar conquista de 39 mil jogos
 * que ninguem abriu e o custo sem a contrapartida.
 *
 * <p>Abrir a pagina do jogo e o sinal de interesse, e nao exige nada do usuario. Nao da pra usar a
 * aba "Conquistas" como gatilho porque ela so aparece quando ja existem conquistas gravadas
 * ({@code *ngIf="temConquistas"}) — seria invisivel exatamente nos jogos que precisam da coleta.
 *
 * <p><b>Cada jogo e consultado uma vez.</b> Quem garante isso e {@code games.achievements_checked_at},
 * carimbado inclusive quando a Steam devolve esquema vazio. Sem essa marcacao, jogo sem conquista
 * seria reconsultado a cada visita.
 *
 * <p>Roda fora da requisicao: a resposta de conquistas sai na hora (vazia, nesta primeira visita) e
 * a aba aparece na proxima. Bloquear a requisicao esperando duas chamadas a Steam atrasaria a
 * pagina de todo mundo pra beneficiar so o primeiro visitante.
 */
@Service
public class ServicoConquistasSobDemanda {
  private static final Logger log = LoggerFactory.getLogger(ServicoConquistasSobDemanda.class);

  private final RepositorioJogos jogos;
  private final ServicoCatalogo catalogo;
  private final TaskExecutor executor;

  /**
   * Jogos com coleta em andamento.
   *
   * <p>O carimbo de {@code achievements_checked_at} so e gravado no fim, entao sem esta trava as
   * visitas simultaneas ao mesmo jogo (e as varias que uma unica pessoa gera ao recarregar)
   * disparariam a mesma coleta em paralelo, multiplicando chamadas a Steam sem necessidade.
   */
  private final Set<Long> emAndamento = ConcurrentHashMap.newKeySet();

  /** Intervalo minimo entre dois preenchimentos completos do mesmo jogo. */
  private static final Duration INTERVALO_MINIMO = Duration.ofHours(12);

  /** Ultimo preenchimento completo por steam_app_id (ver agendarPreenchimentoCompleto). */
  private final Map<Integer, Instant> preenchidoEm = new ConcurrentHashMap<>();

  ServicoConquistasSobDemanda(
      RepositorioJogos jogos,
      ServicoCatalogo catalogo,
      @Qualifier("executorColetaManual") TaskExecutor executor
  ) {
    this.jogos = jogos;
    this.catalogo = catalogo;
    this.executor = executor;
  }

  /**
   * Agenda a coleta se este jogo ainda nao foi consultado.
   *
   * <p>Silencioso e sem efeito quando o jogo nao tem {@code steam_app_id}, ja foi verificado ou ja
   * esta em coleta. Nunca lanca: e um efeito colateral de uma visita, e falhar aqui nao pode
   * derrubar a pagina do jogo.
   *
   * @return {@code true} se ha coleta em andamento para este jogo — inclusive uma disparada por
   *     outra visita simultanea. O controlador devolve isso na resposta para o frontend saber que
   *     vale reconsultar em alguns segundos; sem esse sinal, ele teria que ou tentar de novo em
   *     todo jogo sem conquista (a maioria, desperdicio) ou exigir um F5 do usuario.
   */
  /**
   * Refaz o jogo inteiro quando aparece conquista desbloqueada que o catalogo nao conhece.
   *
   * <p>Mesma coisa que o botao de admin "Preencher tudo agora" ({@link
   * ServicoCatalogo#preencherTudoDoJogo}): metadados Steam (forcados), detalhes e conquistas, na
   * ordem. E o cenario do jogo live-service — Dead by Daylight tinha 303 conquistas no catalogo e
   * 311 na Steam, e as 8 novas apareciam no perfil sem icone. Coletar so as conquistas resolveria
   * o icone, mas quem ganha conquista nova costuma ter ganhado capa/descricao/review novos
   * tambem, entao vale atualizar tudo de uma vez.
   *
   * <p>Nao usa a fila de {@code listarPendentesConquistas}: aquela fila e so pra jogo que nunca
   * foi coletado (ver o NOT EXISTS em {@link RepositorioJogos#listarPendentesConquistas}).
   *
   * <p>Dois freios, porque o gatilho e uma visita a perfil e pode repetir muito: a trava de
   * {@code emAndamento} (compartilhada com a coleta por pagina de jogo) e um intervalo minimo por
   * jogo. O intervalo importa porque conquista oculta/removida nao existe nem no esquema da Steam
   * — sem ele, jogo assim seria refeito em cada visita pra sempre. O cache e em memoria: reiniciar
   * o backend libera um preenchimento extra por jogo, o que e barato e aceitavel.
   *
   * <p>Nunca lanca: e efeito colateral de uma leitura de perfil.
   */
  public void agendarPreenchimentoCompleto(Collection<Integer> steamAppIds) {
    try {
      if (steamAppIds.isEmpty()) return;
      Map<Integer, String> slugs = jogos.buscarSlugsPorSteamAppIds(List.copyOf(steamAppIds));
      Instant agora = Instant.now();
      for (Map.Entry<Integer, String> entrada : slugs.entrySet()) {
        String slug = entrada.getValue();
        if (slug == null) continue;
        Instant ultimo = preenchidoEm.get(entrada.getKey());
        if (ultimo != null && ultimo.isAfter(agora.minus(INTERVALO_MINIMO))) continue;
        agendarPreenchimentoDe(entrada.getKey(), slug);
      }
    } catch (RuntimeException erro) {
      log.warn("Falha agendando preenchimento completo: {}", erro.toString());
    }
  }

  private void agendarPreenchimentoDe(int steamAppId, String slug) {
    var jogo = jogos.buscarIdESteamAppIdPorSlug(slug).orElse(null);
    if (jogo == null || !emAndamento.add(jogo.id())) return;
    preenchidoEm.put(steamAppId, Instant.now());
    executor.execute(() -> {
      try {
        log.info("Conquista fora do catalogo: refazendo o jogo {} (app {}) por inteiro", slug, steamAppId);
        catalogo.preencherTudoDoJogo(slug);
      } catch (RuntimeException erro) {
        log.warn("Falha preenchendo tudo do jogo {}: {}", slug, erro.toString());
      } finally {
        emAndamento.remove(jogo.id());
      }
    });
  }

  public boolean agendarSeNecessario(String slug) {
    try {
      var jogo = jogos.buscarIdESteamAppIdPorSlug(slug).orElse(null);
      if (jogo == null || jogo.steamAppId() == null) {
        return false;
      }
      if (!jogos.precisaColetarConquistas(jogo.id())) {
        return false;
      }
      if (!emAndamento.add(jogo.id())) {
        // Outra visita ja disparou: nao duplica a coleta, mas o frontend deve esperar por ela.
        return true;
      }
      executor.execute(() -> {
        try {
          catalogo.coletarConquistasDoJogo(jogo.id(), jogo.steamAppId());
        } catch (RuntimeException erro) {
          log.warn("Falha coletando conquistas sob demanda do jogo {}: {}", jogo.id(), erro.toString());
        } finally {
          emAndamento.remove(jogo.id());
        }
      });
      return true;
    } catch (RuntimeException erro) {
      log.warn("Falha agendando conquistas sob demanda de {}: {}", slug, erro.toString());
      return false;
    }
  }
}
