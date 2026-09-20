package com.ofertagames.backend.jogos;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.ofertagames.backend.itad.*;
import com.ofertagames.backend.steam.ServicoSteam;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ServicoRankingJogosTest {
  @Test void soExpiraRankComTodasAsFontesDisponiveis() {
    var itad = mock(ClienteItad.class);
    var steam = mock(ServicoSteam.class);
    var jogos = mock(RepositorioJogos.class);
    when(steam.listarAppsDaBusca(anyString(), anyInt())).thenReturn(List.of(10));
    when(itad.buscarIdsPorAppSteam(anyList())).thenReturn(Map.of(10, "id"));
    when(itad.buscarMaisPopulares(anyInt(), anyInt())).thenReturn(List.of(new ItemPopularItad(4, "id", "jogo", "Jogo")));
    when(itad.buscarOfertas(anyInt(), anyInt())).thenReturn(new RespostaOfertasItad(false, null,
        List.of(new ItemOfertaItad("id", "jogo", "Jogo", null, null))));
    var servico = new ServicoRankingJogos(itad, steam, jogos);
    servico.atualizarRanking();
    verify(jogos).atualizarRanksPorItadId(Map.of("id", 1), true);
    when(itad.buscarMaisPopulares(anyInt(), anyInt())).thenThrow(new RuntimeException("timeout"));
    servico.atualizarRanking();
    verify(jogos).atualizarRanksPorItadId(Map.of("id", 1), false);
  }
}
