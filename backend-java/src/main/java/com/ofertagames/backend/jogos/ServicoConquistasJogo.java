package com.ofertagames.backend.jogos;

import com.ofertagames.backend.conexoes.ServicoConexoesSteam;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class ServicoConquistasJogo {
  private final RepositorioJogos jogos;
  private final ServicoConexoesSteam conexoesSteam;

  ServicoConquistasJogo(RepositorioJogos jogos, ServicoConexoesSteam conexoesSteam) {
    this.jogos = jogos;
    this.conexoesSteam = conexoesSteam;
  }

  public Optional<RespostaConquistas> buscar(String slug, String visitanteId) {
    Optional<JogoESteamAppId> jogo = jogos.buscarIdESteamAppIdPorSlug(slug);
    if (jogo.isEmpty()) {
      return Optional.empty();
    }

    List<ConquistaJogo> catalogo = jogos.listarConquistas(jogo.get().id());
    Integer steamAppId = jogo.get().steamAppId();
    Map<String, Instant> desbloqueadas = visitanteId != null && steamAppId != null
        ? conexoesSteam.conquistasDesbloqueadas(visitanteId, steamAppId)
        : Map.of();

    List<ConquistaComProgresso> comProgresso = catalogo.stream()
        .map(conquista -> new ConquistaComProgresso(
            conquista.nome(),
            conquista.descricao(),
            conquista.iconeUrl(),
            conquista.percentualGlobal(),
            desbloqueadas.containsKey(conquista.apiName()),
            desbloqueadas.get(conquista.apiName())))
        .toList();

    // "Mais facil" = a nao desbloqueada que mais gente ja pegou globalmente.
    ConquistaComProgresso proxima = comProgresso.stream()
        .filter(c -> !c.desbloqueada())
        .max(Comparator.comparing(c -> c.percentualGlobal() == null ? -1.0 : c.percentualGlobal()))
        .orElse(null);

    int total = comProgresso.size();
    int desbloqueadasCount = (int) comProgresso.stream().filter(ConquistaComProgresso::desbloqueada).count();
    int percentual = total == 0 ? 0 : Math.round(desbloqueadasCount * 100f / total);

    // coletando = false aqui: quem sabe se ha coleta em andamento e o controlador, que a dispara.
    return Optional.of(new RespostaConquistas(total, desbloqueadasCount, percentual, proxima, comProgresso, false));
  }
}
