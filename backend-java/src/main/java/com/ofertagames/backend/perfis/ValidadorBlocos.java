package com.ofertagames.backend.perfis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Valida o CONTEUDO dos blocos do perfil — o que e renderizado pra todo visitante (issue #25).
 *
 * <p>{@link ServicoPerfis#salvarBlocos} ja conferia tipo, tamanho, cor e opacidade, mas deixava
 * passar tres coisas que o frontend so limitava no navegador (e qualquer um contorna chamando a API
 * direto):
 *
 * <ul>
 *   <li><b>Fundo gradiente vai direto num {@code style.background}</b>. O Angular nao sanitiza
 *       estilo desde a v10, entao {@code url(https://atacante/px.gif)} fazia TODO visitante do
 *       perfil carregar a URL — vazando IP, navegador e horario de quem visita. So o formato que o
 *       editor gera e aceito.</li>
 *   <li><b>Imagem (de fundo ou do bloco de imagem) de qualquer site</b>: mesmo vazamento, e
 *       permitia exibir imagem impropria sob o dominio do site. Agora so do proprio bucket do
 *       usuario — com uma excecao, abaixo.</li>
 *   <li><b>Texto sem limite</b>: 20 blocos com megabytes cada, num banco com cota de 500 MB.</li>
 * </ul>
 *
 * <p><b>URL externa ja salva continua valendo.</b> Um perfil em producao ja usava imagem externa
 * (conferido em 13/09/2026); recusar faria o dono nao conseguir salvar mais nada no proprio perfil.
 * A URL e aceita se for exatamente a mesma que ja estava gravada naquele bloco — trocar por outra
 * URL externa nao passa.
 */
final class ValidadorBlocos {
  static final int TITULO_MAXIMO = 120;
  static final int CONTEUDO_MAXIMO = 1_400;
  /** Bloco de imagem guarda JSON {url, zoom, positionX, positionY}: a URL do bucket passa de 200. */
  static final int CONTEUDO_IMAGEM_MAXIMO = 2_000;
  static final int LINKS_MAXIMOS = 10;

  private static final Pattern GRADIENTE = Pattern.compile("^linear-gradient\\(135deg, #[0-9a-fA-F]{6}, #[0-9a-fA-F]{6}\\)$");
  private static final Set<String> ESQUEMAS_PROIBIDOS = Set.of("javascript:", "data:", "vbscript:", "file:", "blob:");

  private final ObjectMapper json;

  ValidadorBlocos(ObjectMapper json) {
    this.json = json;
  }

  /**
   * @param anteriores blocos hoje gravados, por id — base da excecao "URL externa ja salva"
   * @return mensagem do primeiro problema, ou vazio se o bloco e valido
   */
  Optional<String> validar(String usuarioId, RepositorioBlocosPerfil.BlocoPerfil bloco,
      Map<String, RepositorioBlocosPerfil.BlocoPerfil> anteriores) {
    RepositorioBlocosPerfil.BlocoPerfil anterior = anteriores.get(bloco.id());

    if (bloco.titulo() != null && bloco.titulo().length() > TITULO_MAXIMO) {
      return Optional.of("Título do bloco muito longo (máximo " + TITULO_MAXIMO + " caracteres)");
    }

    if ("gradiente".equals(bloco.tipoFundo()) && (bloco.valorFundo() == null || !GRADIENTE.matcher(bloco.valorFundo()).matches())) {
      return Optional.of("Gradiente de fundo inválido");
    }
    if ("imagem".equals(bloco.tipoFundo())) {
      String anteriorFundo = anterior == null ? null : anterior.valorFundo();
      if (!urlImagemPermitida(usuarioId, bloco.valorFundo(), anteriorFundo)) {
        return Optional.of("A imagem de fundo precisa ser enviada pelo site");
      }
    }

    String conteudo = bloco.conteudo() == null ? "" : bloco.conteudo();
    return switch (bloco.tipo()) {
      case "imagem" -> validarImagem(usuarioId, conteudo, anterior);
      case "links" -> validarLinks(conteudo);
      default -> conteudo.length() > CONTEUDO_MAXIMO
          ? Optional.of("Texto do bloco muito longo (máximo " + CONTEUDO_MAXIMO + " caracteres)")
          : Optional.empty();
    };
  }

  private Optional<String> validarImagem(String usuarioId, String conteudo, RepositorioBlocosPerfil.BlocoPerfil anterior) {
    if (conteudo.isBlank()) return Optional.empty();
    if (conteudo.length() > CONTEUDO_IMAGEM_MAXIMO) return Optional.of("Dados da imagem inválidos");
    String url = urlDoJson(conteudo);
    if (url == null) return Optional.of("Dados da imagem inválidos");
    String urlAnterior = anterior == null || anterior.conteudo() == null ? null : urlDoJson(anterior.conteudo());
    return urlImagemPermitida(usuarioId, url, urlAnterior)
        ? Optional.empty()
        : Optional.of("A imagem do bloco precisa ser enviada pelo site");
  }

  private Optional<String> validarLinks(String conteudo) {
    if (conteudo.length() > CONTEUDO_MAXIMO) {
      return Optional.of("Lista de links muito longa (máximo " + CONTEUDO_MAXIMO + " caracteres)");
    }
    List<String> linhas = conteudo.lines().map(String::trim).filter(l -> !l.isEmpty()).toList();
    if (linhas.size() > LINKS_MAXIMOS) return Optional.of("Use no máximo " + LINKS_MAXIMOS + " links");
    for (String linha : linhas) {
      String minuscula = linha.toLowerCase(Locale.ROOT).replaceAll("\\s", "");
      // O [href] do Angular ja neutraliza "javascript:" na tela, mas o dado nao deve nem ser
      // gravado: outra tela, um e-mail ou uma API futura podem nao ter a mesma protecao.
      if (ESQUEMAS_PROIBIDOS.stream().anyMatch(minuscula::contains)) return Optional.of("Link inválido: use endereços http ou https");
    }
    return Optional.empty();
  }

  /** Do proprio bucket do usuario, ou a mesma URL que ja estava gravada nesse bloco. */
  static boolean urlImagemPermitida(String usuarioId, String url, String urlJaGravada) {
    if (url == null || url.isBlank()) return false;
    if (Objects.equals(url, urlJaGravada)) return true;
    return urlDoProprioBucket(usuarioId, url);
  }

  static boolean urlDoProprioBucket(String usuarioId, String url) {
    String padrao = "^https://[a-z0-9-]+\\.supabase\\.co/storage/v1/object/public/avatars/"
        + Pattern.quote(usuarioId) + "/[^\\s'\"()<>]{1,900}$";
    return url != null && url.trim().matches(padrao);
  }

  private String urlDoJson(String conteudo) {
    try {
      JsonNode no = json.readTree(conteudo);
      JsonNode url = no == null ? null : no.get("url");
      return url == null || !url.isTextual() ? null : url.asText();
    } catch (Exception erro) {
      return null;
    }
  }
}
