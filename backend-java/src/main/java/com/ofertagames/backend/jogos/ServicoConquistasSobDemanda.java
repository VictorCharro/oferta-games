package com.ofertagames.backend.jogos;

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
