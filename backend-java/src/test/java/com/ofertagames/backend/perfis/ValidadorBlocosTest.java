package com.ofertagames.backend.perfis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValidadorBlocosTest {
  private static final String USUARIO = "0a6eb06b-756e-4434-899b-33420bed8609";
  private static final String BUCKET = "https://gxukrzmiloqdmtcgigqm.supabase.co/storage/v1/object/public/avatars/" + USUARIO + "/blocks/b1-123";
  private final ValidadorBlocos validador = new ValidadorBlocos(new ObjectMapper());

  private static RepositorioBlocosPerfil.BlocoPerfil bloco(String tipo, String titulo, String conteudo, String tipoFundo, String valorFundo) {
    return new RepositorioBlocosPerfil.BlocoPerfil("b1", tipo, titulo, conteudo, 0, "medio", true, tipoFundo, valorFundo, 0, null, null);
  }

  private boolean valido(RepositorioBlocosPerfil.BlocoPerfil bloco) {
    return validador.validar(USUARIO, bloco, Map.of()).isEmpty();
  }

  @Test
  void gradienteSoNoFormatoQueOEditorGera() {
    assertTrue(valido(bloco("texto", "", "", "gradiente", "linear-gradient(135deg, #0ea5e9, #312e81)")));
    // O ataque da issue: carregar URL de terceiro no navegador de todo visitante.
    assertFalse(valido(bloco("texto", "", "", "gradiente", "url(https://atacante.example/px.gif)")));
    assertFalse(valido(bloco("texto", "", "", "gradiente", "linear-gradient(135deg, #0ea5e9, #312e81), url(https://x.example/a)")));
  }

  @Test
  void imagemSoDoBucketDoProprioUsuario() {
    assertTrue(valido(bloco("imagem", "", "{\"url\":\"" + BUCKET + "\",\"zoom\":1}", "padrao", null)));
    assertFalse(valido(bloco("imagem", "", "{\"url\":\"https://i.pinimg.com/x.jpg\"}", "padrao", null)));
    // Bucket de OUTRO usuario tambem nao.
    assertFalse(valido(bloco("imagem", "", "{\"url\":\"https://gxukrzmiloqdmtcgigqm.supabase.co/storage/v1/object/public/avatars/outro-uuid/a.png\"}", "padrao", null)));
    assertTrue(valido(bloco("texto", "", "", "imagem", BUCKET)));
    assertFalse(valido(bloco("texto", "", "", "imagem", "https://atacante.example/fundo.png')")));
  }

  /** Perfil em producao ja tinha imagem externa: salvar de novo sem mexer nela nao pode quebrar. */
  @Test
  void urlExternaJaGravadaNoMesmoBlocoContinuaValendo() {
    String externo = "{\"url\":\"https://i.pinimg.com/236x/94/36/1e/foto.jpg\",\"zoom\":1.15,\"positionX\":0,\"positionY\":6}";
    var gravado = bloco("imagem", "Imagem", externo, "padrao", null);
    // Mesmo ajuste diferente (zoom), a URL e a mesma: passa.
    var reenviado = bloco("imagem", "Imagem", externo.replace("1.15", "1.3"), "padrao", null);
    assertTrue(validador.validar(USUARIO, reenviado, Map.of("b1", gravado)).isEmpty());
    // Trocar por OUTRA URL externa: nao passa.
    var trocado = bloco("imagem", "Imagem", "{\"url\":\"https://outro.example/b.jpg\"}", "padrao", null);
    assertFalse(validador.validar(USUARIO, trocado, Map.of("b1", gravado)).isEmpty());
  }

  @Test
  void textoTituloELinksTemLimite() {
    assertFalse(valido(bloco("texto", "a".repeat(ValidadorBlocos.TITULO_MAXIMO + 1), "", "padrao", null)));
    assertFalse(valido(bloco("texto", "", "a".repeat(ValidadorBlocos.CONTEUDO_MAXIMO + 1), "padrao", null)));
    assertTrue(valido(bloco("texto", "Sobre mim", "a".repeat(ValidadorBlocos.CONTEUDO_MAXIMO), "padrao", null)));
    assertFalse(valido(bloco("links", "", "x.com\n".repeat(ValidadorBlocos.LINKS_MAXIMOS + 1), "padrao", null)));
  }

  @Test
  void linksNaoAceitamEsquemaPerigoso() {
    assertTrue(valido(bloco("links", "", "Twitch - twitch.tv/eu\nYouTube - https://youtube.com/@eu", "padrao", null)));
    assertFalse(valido(bloco("links", "", "Clique - javascript:alert(1)", "padrao", null)));
    assertFalse(valido(bloco("links", "", "Clique - JavaScript :alert(1)", "padrao", null)));
    assertFalse(valido(bloco("links", "", "Arquivo - data:text/html,<script>", "padrao", null)));
  }
}
