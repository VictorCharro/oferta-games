package com.ofertagames.backend.sincronizacao;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ofertagames.backend.alertas.NotificadorWhatsapp;
import org.junit.jupiter.api.Test;

/**
 * Ver {@link NotificadorWhatsapp}: falha de job de coleta e um dos dois gatilhos do aviso por
 * WhatsApp, ao lado de erro novo/reaberto em {@code erros.RegistroErrosTest}.
 */
class ServicoExecucaoColetaTest {

  @Test void jobComSucessoNaoAvisaWhatsapp() {
    RepositorioControleColeta controle = mock(RepositorioControleColeta.class);
    EstadoColeta estado = mock(EstadoColeta.class);
    NotificadorWhatsapp whatsapp = mock(NotificadorWhatsapp.class);
    when(controle.tentarAdquirir(anyString())).thenReturn(true);

    boolean rodou = new ServicoExecucaoColeta(controle, estado, whatsapp)
        .executar("precos", () -> new ResultadoRodadaColeta(10, 5));

    assertTrue(rodou);
    verifyNoInteractions(whatsapp);
  }

  @Test void jobQueFalhaAvisaWhatsappComTipoEResumoDoErro() {
    RepositorioControleColeta controle = mock(RepositorioControleColeta.class);
    EstadoColeta estado = mock(EstadoColeta.class);
    NotificadorWhatsapp whatsapp = mock(NotificadorWhatsapp.class);
    when(controle.tentarAdquirir(anyString())).thenReturn(true);

    ServicoExecucaoColeta servico = new ServicoExecucaoColeta(controle, estado, whatsapp);
    assertThrows(IllegalStateException.class, () -> servico.executar("ranking", () -> {
      throw new IllegalStateException("bad sql grammar");
    }));

    verify(whatsapp).avisarFalhaDeColeta(eq("ranking"), contains("bad sql grammar"));
  }

  /** Trava ja tomada = job nem roda, nao e falha: nao deve gerar aviso nenhum. */
  @Test void travaJaTomadaNaoAvisaWhatsapp() {
    RepositorioControleColeta controle = mock(RepositorioControleColeta.class);
    EstadoColeta estado = mock(EstadoColeta.class);
    NotificadorWhatsapp whatsapp = mock(NotificadorWhatsapp.class);
    when(controle.tentarAdquirir(anyString())).thenReturn(false);

    boolean rodou = new ServicoExecucaoColeta(controle, estado, whatsapp)
        .executar("precos", () -> {
          throw new IllegalStateException("nao deveria rodar");
        });

    assertFalse(rodou);
    verify(whatsapp, never()).avisarFalhaDeColeta(any(), any());
  }
}
