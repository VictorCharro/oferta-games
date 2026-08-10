package com.ofertagames.backend.jogos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ofertagames.backend.instantgaming.ServicoInstantGaming;
import com.ofertagames.backend.itad.ClienteItad;
import com.ofertagames.backend.itad.DinheiroItad;
import com.ofertagames.backend.itad.LojaItad;
import com.ofertagames.backend.itad.OfertaPrecoItad;
import com.ofertagames.backend.itad.ResultadoPrecoItad;
import com.ofertagames.backend.notificacoes.RepositorioNotificacoes;
import com.ofertagames.backend.steam.ServicoSteam;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

// Cobre a logica adicionada em 10/08/2026: cooldown do refresh manual e cascata de preco pras
// DLCs do jogo. Nao usa Spring context (mocks puros) pra ficar rapido.
class ServicoCatalogoTest {

  @Mock private RepositorioJogos jogos;
  @Mock private ClienteItad itad;
  @Mock private ServicoSteam steam;
  @Mock private RepositorioNotificacoes notificacoes;
  @Mock private ServicoInstantGaming instantGaming;

  private ServicoCatalogo servico;

  @BeforeEach
  void configurar() {
    MockitoAnnotations.openMocks(this);
    servico = new ServicoCatalogo(jogos, itad, steam, notificacoes, instantGaming);
  }

  private static JogoParaAtualizar jogo(long id, String itadId, Instant ultimoRefreshManual) {
    return new JogoParaAtualizar(id, "Jogo " + id, itadId, null, false, null, ultimoRefreshManual);
  }

  private static ResultadoPrecoItad ofertaItad(String itadId, BigDecimal preco) {
    OfertaPrecoItad oferta = new OfertaPrecoItad(
        new LojaItad(1, "Steam"),
        new DinheiroItad(preco, "BRL"),
        new DinheiroItad(preco.multiply(BigDecimal.valueOf(2)), "BRL"),
        "https://loja.exemplo/jogo",
        null);
    return new ResultadoPrecoItad(itadId, List.of(oferta));
  }

  @Test
  void lancaNaoEncontradoQuandoSlugNaoExiste() {
    when(jogos.buscarParaAtualizar("inexistente")).thenReturn(Optional.empty());

    assertThrows(ServicoCatalogo.JogoNaoEncontradoException.class, () -> servico.atualizarPrecos("inexistente"));
  }

  @Test
  void recusaComCooldownQuandoRefreshManualFoiRecente() {
    JogoParaAtualizar jogo = jogo(1L, "itad-1", Instant.now().minusSeconds(60));
    when(jogos.buscarParaAtualizar("jogo")).thenReturn(Optional.of(jogo));

    ServicoCatalogo.RefreshRecenteException erro = assertThrows(
        ServicoCatalogo.RefreshRecenteException.class,
        () -> servico.atualizarPrecos("jogo"));

    // Cooldown de 5min (300s); passaram 60s, entao devem sobrar por volta de 240s.
    assertEquals(240, erro.segundosRestantes(), 2);
    verify(itad, never()).buscarPrecos(anyString());
    verify(jogos, never()).marcarRefreshManual(anyLong());
  }

  @Test
  void permiteRefreshQuandoCooldownJaPassou() {
    JogoParaAtualizar jogo = jogo(1L, "itad-1", Instant.now().minusSeconds(400));
    when(jogos.buscarParaAtualizar("jogo")).thenReturn(Optional.of(jogo));
    when(itad.buscarPrecos("itad-1")).thenReturn(List.of(ofertaItad("itad-1", BigDecimal.TEN)));
    when(jogos.salvarOfertas(anyList())).thenReturn(1);
    when(jogos.listarDlcsDoJogo(1L)).thenReturn(List.of());
    when(instantGaming.atualizarPrecoImediato(anyLong(), anyString())).thenReturn(false);

    ResultadoAtualizacaoJogo resultado = servico.atualizarPrecos("jogo");

    assertEquals(true, resultado.ok());
    assertEquals(1, resultado.updated());
    verify(jogos, times(1)).marcarRefreshManual(1L);
  }

  @Test
  void permiteRefreshQuandoNuncaFoiAtualizadoAntes() {
    JogoParaAtualizar jogo = jogo(1L, "itad-1", null);
    when(jogos.buscarParaAtualizar("jogo")).thenReturn(Optional.of(jogo));
    when(itad.buscarPrecos("itad-1")).thenReturn(List.of(ofertaItad("itad-1", BigDecimal.TEN)));
    when(jogos.salvarOfertas(anyList())).thenReturn(1);
    when(jogos.listarDlcsDoJogo(1L)).thenReturn(List.of());
    when(instantGaming.atualizarPrecoImediato(anyLong(), anyString())).thenReturn(false);

    ResultadoAtualizacaoJogo resultado = servico.atualizarPrecos("jogo");

    assertEquals(1, resultado.updated());
  }

  @Test
  void atualizaPrecoDasDlcsJuntoComOJogoBase() {
    JogoParaAtualizar base = jogo(1L, "itad-base", null);
    JogoParaAtualizar dlc1 = jogo(2L, "itad-dlc1", null);
    JogoParaAtualizar dlc2 = jogo(3L, "itad-dlc2", null);

    when(jogos.buscarParaAtualizar("jogo-base")).thenReturn(Optional.of(base));
    when(jogos.listarDlcsDoJogo(1L)).thenReturn(List.of(
        new ResumoJogo("dlc-1", "DLC 1", null, true, null, null, null, null),
        new ResumoJogo("dlc-2", "DLC 2", null, true, null, null, null, null)));
    when(jogos.buscarParaAtualizarPorSlugs(List.of("dlc-1", "dlc-2"))).thenReturn(List.of(dlc1, dlc2));

    when(itad.buscarPrecos("itad-base")).thenReturn(List.of(ofertaItad("itad-base", BigDecimal.TEN)));
    when(itad.buscarPrecos("itad-dlc1")).thenReturn(List.of(ofertaItad("itad-dlc1", BigDecimal.ONE)));
    when(itad.buscarPrecos("itad-dlc2")).thenReturn(List.of());
    when(jogos.salvarOfertas(anyList())).thenReturn(1);
    when(instantGaming.atualizarPrecoImediato(anyLong(), anyString())).thenReturn(false);

    ResultadoAtualizacaoJogo resultado = servico.atualizarPrecos("jogo-base");

    // base (1 oferta salva) + dlc1 (1 oferta salva) + dlc2 (sem preco, nao conta) = 2.
    assertEquals(2, resultado.updated());
    // Cooldown/refresh manual e so registrado pro jogo principal, nao pras DLCs.
    verify(jogos, times(1)).marcarRefreshManual(1L);
    verify(jogos, never()).marcarRefreshManual(2L);
    verify(jogos, never()).marcarRefreshManual(3L);
  }

  @Test
  void dlcSemFonteDePrecoNaoDerrubaORefreshDoJogoBase() {
    JogoParaAtualizar base = jogo(1L, "itad-base", null);
    JogoParaAtualizar dlcSemPreco = new JogoParaAtualizar(2L, "DLC sem preco", null, null, true, null, null);

    when(jogos.buscarParaAtualizar("jogo-base")).thenReturn(Optional.of(base));
    when(jogos.listarDlcsDoJogo(1L)).thenReturn(List.of(
        new ResumoJogo("dlc-sem-preco", "DLC sem preco", null, true, null, null, null, null)));
    when(jogos.buscarParaAtualizarPorSlugs(List.of("dlc-sem-preco"))).thenReturn(List.of(dlcSemPreco));

    when(itad.buscarPrecos("itad-base")).thenReturn(List.of(ofertaItad("itad-base", BigDecimal.TEN)));
    when(jogos.salvarOfertas(anyList())).thenReturn(1);
    when(instantGaming.atualizarPrecoImediato(anyLong(), anyString())).thenReturn(false);

    ResultadoAtualizacaoJogo resultado = servico.atualizarPrecos("jogo-base");

    assertEquals(1, resultado.updated());
  }

  @Test
  void lancaSemItadQuandoJogoPrincipalNaoTemNenhumaFonteDePreco() {
    JogoParaAtualizar jogo = new JogoParaAtualizar(1L, "Sem fonte", null, null, false, null, null);
    when(jogos.buscarParaAtualizar("jogo")).thenReturn(Optional.of(jogo));
    when(instantGaming.atualizarPrecoImediato(anyLong(), anyString())).thenReturn(false);

    assertThrows(ServicoCatalogo.JogoSemItadException.class, () -> servico.atualizarPrecos("jogo"));
  }
}
