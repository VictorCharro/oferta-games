package com.ofertagames.backend.contato;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ofertagames.backend.contato.ArmazenamentoAnexos.TipoAnexo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class ArmazenamentoAnexosTest {

  private static byte[] bytes(int... valores) {
    byte[] b = new byte[16];
    for (int i = 0; i < valores.length; i++) b[i] = (byte) valores[i];
    return b;
  }

  private static byte[] ascii(int deslocamento, String texto, byte[] base) {
    byte[] t = texto.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(t, 0, base, deslocamento, t.length);
    return base;
  }

  @Test
  void detectaTipoPelosPrimeirosBytes() {
    assertEquals(Optional.of(TipoAnexo.PNG), ArmazenamentoAnexos.detectar(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A)));
    assertEquals(Optional.of(TipoAnexo.JPEG), ArmazenamentoAnexos.detectar(bytes(0xFF, 0xD8, 0xFF, 0xE0)));
    assertEquals(Optional.of(TipoAnexo.GIF), ArmazenamentoAnexos.detectar(ascii(0, "GIF89a", new byte[16])));
    assertEquals(Optional.of(TipoAnexo.WEBP), ArmazenamentoAnexos.detectar(ascii(8, "WEBP", ascii(0, "RIFF", new byte[16]))));
    assertEquals(Optional.of(TipoAnexo.WEBM), ArmazenamentoAnexos.detectar(bytes(0x1A, 0x45, 0xDF, 0xA3)));
    assertEquals(Optional.of(TipoAnexo.MP4), ArmazenamentoAnexos.detectar(ascii(4, "ftypisom", new byte[16])));
    assertEquals(Optional.of(TipoAnexo.MOV), ArmazenamentoAnexos.detectar(ascii(4, "ftypqt  ", new byte[16])));
  }

  @Test
  void recusaArquivoQueNaoEImagemNemVideoMesmoComExtensaoDeImagem(@TempDir Path dir) {
    ArmazenamentoAnexos armazenamento = new ArmazenamentoAnexos(dir.toString());
    MockMultipartFile html = new MockMultipartFile("anexos", "foto.png", "image/png", "<html><script>alert(1)</script>".getBytes());
    assertThrows(ResponseStatusException.class, () -> armazenamento.validar(List.of(html)));
  }

  @Test
  void recusaMaisQueTresAnexosEImagemAcimaDoLimite(@TempDir Path dir) {
    ArmazenamentoAnexos armazenamento = new ArmazenamentoAnexos(dir.toString());
    byte[] png = bytes(0x89, 'P', 'N', 'G');
    MockMultipartFile pequena = new MockMultipartFile("anexos", "a.png", "image/png", png);
    assertThrows(ResponseStatusException.class, () -> armazenamento.validar(List.of(pequena, pequena, pequena, pequena)));

    byte[] grande = new byte[(int) ArmazenamentoAnexos.MAX_IMAGEM + 1];
    System.arraycopy(png, 0, grande, 0, png.length);
    assertThrows(ResponseStatusException.class, () -> armazenamento.validar(List.of(new MockMultipartFile("anexos", "g.png", "image/png", grande))));
  }

  @Test
  void gravaComNomeGeradoELimpaNomeOriginal(@TempDir Path dir) throws Exception {
    ArmazenamentoAnexos armazenamento = new ArmazenamentoAnexos(dir.toString());
    MockMultipartFile png = new MockMultipartFile("anexos", "C:\\Users\\x\\../../erro.png", "text/html", bytes(0x89, 'P', 'N', 'G'));
    var validado = armazenamento.validar(List.of(png)).get(0);
    assertEquals("erro.png", validado.nomeOriginal());
    String nome = armazenamento.gravar(validado);
    assertTrue(nome.endsWith(".png"));
    assertTrue(Files.exists(dir.resolve(nome)));
    assertThrows(IllegalArgumentException.class, () -> armazenamento.caminho("../fora.png"));
    armazenamento.remover(nome);
    assertTrue(Files.notExists(dir.resolve(nome)));
  }
}
