package com.ofertagames.backend.contato;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Anexos do Fale conosco no disco da VM (volume anexos_contato). So o registro vai pro banco
 * (contact_attachments).
 *
 * <p>O tipo e decidido pelos primeiros bytes do arquivo, nao pelo Content-Type nem pela extensao,
 * que o cliente controla: um HTML renomeado pra .png e recusado. O arquivo e gravado com nome
 * gerado aqui (uuid) e so e servido pro admin, com o tipo detectado.
 */
@Component
class ArmazenamentoAnexos {
  static final int MAX_ANEXOS = 3;
  static final long MAX_IMAGEM = 8L * 1024 * 1024;
  static final long MAX_VIDEO = 50L * 1024 * 1024;
  /** Teto do disco inteiro de anexos: acima disso novos anexos sao recusados (a mensagem nao). */
  static final long MAX_TOTAL = 5L * 1024 * 1024 * 1024;

  private final Path diretorio;

  ArmazenamentoAnexos(@Value("${app.contato.anexos-dir:${java.io.tmpdir}/oferta-games-anexos}") String diretorio) {
    this.diretorio = Path.of(diretorio);
  }

  /** Confere quantidade, tipo real e tamanho de todos antes de gravar qualquer um. */
  List<AnexoValidado> validar(List<MultipartFile> arquivos) {
    List<MultipartFile> enviados = arquivos == null ? List.of() : arquivos.stream().filter(a -> a != null && !a.isEmpty()).toList();
    if (enviados.size() > MAX_ANEXOS) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Envie no máximo " + MAX_ANEXOS + " anexos");
    }
    return enviados.stream().map(this::validar).toList();
  }

  private AnexoValidado validar(MultipartFile arquivo) {
    byte[] cabecalho;
    try (InputStream entrada = arquivo.getInputStream()) {
      cabecalho = entrada.readNBytes(16);
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Não foi possível ler o anexo");
    }
    TipoAnexo tipo = detectar(cabecalho)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Anexo inválido. Envie imagem (PNG, JPG, WEBP, GIF) ou vídeo (MP4, WEBM, MOV)"));
    long limite = tipo.video ? MAX_VIDEO : MAX_IMAGEM;
    if (arquivo.getSize() > limite) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          (tipo.video ? "Vídeo" : "Imagem") + " muito grande: o limite é " + (limite / (1024 * 1024)) + " MB");
    }
    return new AnexoValidado(arquivo, tipo, nomeOriginal(arquivo.getOriginalFilename()));
  }

  static Optional<TipoAnexo> detectar(byte[] b) {
    if (comeca(b, 0, 0x89, 'P', 'N', 'G')) return Optional.of(TipoAnexo.PNG);
    if (comeca(b, 0, 0xFF, 0xD8, 0xFF)) return Optional.of(TipoAnexo.JPEG);
    if (comeca(b, 0, 'G', 'I', 'F', '8')) return Optional.of(TipoAnexo.GIF);
    if (comeca(b, 0, 'R', 'I', 'F', 'F') && comeca(b, 8, 'W', 'E', 'B', 'P')) return Optional.of(TipoAnexo.WEBP);
    if (comeca(b, 0, 0x1A, 0x45, 0xDF, 0xA3)) return Optional.of(TipoAnexo.WEBM);
    if (comeca(b, 4, 'f', 't', 'y', 'p')) {
      String marca = b.length >= 12 ? new String(b, 8, 4, StandardCharsets.US_ASCII) : "";
      return Optional.of(marca.startsWith("qt") ? TipoAnexo.MOV : TipoAnexo.MP4);
    }
    return Optional.empty();
  }

  private static boolean comeca(byte[] b, int inicio, int... esperado) {
    if (b.length < inicio + esperado.length) return false;
    for (int i = 0; i < esperado.length; i++) {
      if ((b[inicio + i] & 0xFF) != esperado[i]) return false;
    }
    return true;
  }

  private static String nomeOriginal(String nome) {
    if (nome == null || nome.isBlank()) return null;
    String limpo = Path.of(nome.replace('\\', '/')).getFileName().toString().replaceAll("[\\p{Cntrl}]", "").trim();
    return limpo.isEmpty() ? null : (limpo.length() > 120 ? limpo.substring(limpo.length() - 120) : limpo);
  }

  /** Grava e devolve o nome no disco. Quem chama apaga com {@link #remover} se o resto falhar. */
  String gravar(AnexoValidado anexo) {
    String nome = UUID.randomUUID() + "." + anexo.tipo().extensao;
    try {
      Files.createDirectories(diretorio);
      try (InputStream entrada = anexo.arquivo().getInputStream()) {
        Files.copy(entrada, diretorio.resolve(nome), StandardCopyOption.REPLACE_EXISTING);
      }
      return nome;
    } catch (IOException e) {
      throw new IllegalStateException("Falha ao gravar anexo de contato", e);
    }
  }

  void remover(String nomeNoDisco) {
    try {
      Files.deleteIfExists(caminho(nomeNoDisco));
    } catch (IOException | IllegalArgumentException ignorado) {
      // Arquivo orfao no disco e aceitavel; erro aqui nao pode esconder o erro original.
    }
  }

  Path caminho(String nomeNoDisco) {
    Path arquivo = diretorio.resolve(nomeNoDisco).normalize();
    if (!arquivo.getParent().equals(diretorio.normalize())) throw new IllegalArgumentException("Nome de anexo inválido");
    return arquivo;
  }

  enum TipoAnexo {
    PNG("image/png", "png", false), JPEG("image/jpeg", "jpg", false), WEBP("image/webp", "webp", false), GIF("image/gif", "gif", false),
    MP4("video/mp4", "mp4", true), WEBM("video/webm", "webm", true), MOV("video/quicktime", "mov", true);

    final String contentType;
    final String extensao;
    final boolean video;

    TipoAnexo(String contentType, String extensao, boolean video) {
      this.contentType = contentType;
      this.extensao = extensao;
      this.video = video;
    }
  }

  record AnexoValidado(MultipartFile arquivo, TipoAnexo tipo, String nomeOriginal) {
    long tamanho() { return arquivo.getSize(); }
  }
}
