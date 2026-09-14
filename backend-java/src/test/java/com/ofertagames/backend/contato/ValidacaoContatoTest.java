package com.ofertagames.backend.contato;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ofertagames.backend.contato.ControladorContato.EntradaContato;
import com.ofertagames.backend.contato.ControladorContato.MensagemValidada;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ValidacaoContatoTest {

  private static EntradaContato entrada(String tipo, String mensagem, String email, String pagina) {
    return new EntradaContato(tipo, mensagem, email, pagina, null);
  }

  @Test
  void normalizaTipoEmailVazioEPagina() {
    MensagemValidada m = ControladorContato.validar(entrada(" Sugestao ", "  Poderia ter filtro por loja  ", "  ", "/jogo/hades?ref=x"));
    assertEquals("sugestao", m.tipo());
    assertEquals("Poderia ter filtro por loja", m.texto());
    assertNull(m.email());
    assertEquals("/jogo/hades", m.pagina());
  }

  @Test
  void descartaPaginaQueNaoEhCaminhoInterno() {
    assertNull(ControladorContato.validar(entrada("outro", "mensagem longa o bastante", null, "https://evil.com/x")).pagina());
    assertNull(ControladorContato.validar(entrada("outro", "mensagem longa o bastante", null, "//evil.com")).pagina());
  }

  @Test
  void recusaTipoDesconhecidoMensagemCurtaEEmailInvalido() {
    assertThrows(ResponseStatusException.class, () -> ControladorContato.validar(entrada("spam", "mensagem longa o bastante", null, null)));
    assertThrows(ResponseStatusException.class, () -> ControladorContato.validar(entrada("elogio", "curta", null, null)));
    assertThrows(ResponseStatusException.class, () -> ControladorContato.validar(entrada("elogio", "x".repeat(2001), null, null)));
    assertThrows(ResponseStatusException.class, () -> ControladorContato.validar(entrada("elogio", "mensagem longa o bastante", "nao-e-email", null)));
  }
}
