package com.ofertagames.backend.erros;

import com.ofertagames.backend.alertas.NotificadorWhatsapp;
import com.ofertagames.backend.comum.LimitePorJanela;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Rastreamento de erros proprio (issue #29): guarda erros do navegador e 500 do backend em
 * app_errors, agrupados por assinatura, pra aba "Erros" do admin.
 *
 * <p>Nunca pode derrubar quem chama: falha ao registrar vira so um log. E tem dois freios pra nao
 * virar vetor de abuso (o endpoint do navegador e anonimo): teto global de relatos por hora e teto
 * de linhas distintas na tabela — erro repetido so incrementa o contador de uma linha existente.
 *
 * <p><b>Aviso por WhatsApp (22/09/2026):</b> so na <b>primeira ocorrencia</b> de uma assinatura
 * nova, ou quando uma ja marcada como resolvida <b>volta a acontecer</b> — nunca a cada ocorrencia
 * de um erro ja ativo e conhecido, que so incrementa {@code occurrences} (ja visivel na aba Erros).
 * Sem essa distincao, um bug que afeta muita gente de uma vez geraria uma rajada de mensagens bem
 * na hora que mais importa saber — e e exatamente esse volume que faz o CallMeBot (nao-oficial,
 * sem SLA) parar de entregar. Ver {@link NotificadorWhatsapp}.
 */
@Component
public class RegistroErros {
  private static final Logger logger = LoggerFactory.getLogger(RegistroErros.class);

  static final int RELATOS_NAVEGADOR_POR_HORA = 600;
  static final int MAX_ERROS_DISTINTOS = 5000;

  private final JdbcClient jdbc;
  private final NotificadorWhatsapp whatsapp;
  private final LimitePorJanela relatosNavegador = new LimitePorJanela(RELATOS_NAVEGADOR_POR_HORA, Duration.ofHours(1));

  RegistroErros(JdbcClient jdbc, NotificadorWhatsapp whatsapp) {
    this.jdbc = jdbc;
    this.whatsapp = whatsapp;
  }

  boolean aceitarRelatoNavegador() {
    return relatosNavegador.tentarConsumir();
  }

  public void registrar(String origem, String mensagem, String detalhe, String pagina, String userAgent) {
    try {
      String msg = cortar(mensagem == null || mensagem.isBlank() ? "(sem mensagem)" : mensagem.strip(), 500);
      String det = cortar(detalhe, 4000);
      String assinatura = assinatura(origem, msg, det);

      // Le o estado ANTES do UPDATE abaixo, que ja zera resolved_at: e o unico jeito de saber se
      // esta ocorrencia reabriu um erro resolvido, em vez de so continuar um que ja estava ativo.
      // null = assinatura nunca vista; true = existia e estava resolvida; false = existia e ja ativa.
      Boolean estavaResolvido = jdbc.sql("SELECT resolved_at IS NOT NULL FROM app_errors WHERE signature = :assinatura")
          .param("assinatura", assinatura).query(Boolean.class).optional().orElse(null);

      int atualizadas = jdbc.sql("""
          UPDATE app_errors
          SET occurrences = occurrences + 1, last_seen_at = now(), resolved_at = NULL,
              page_path = coalesce(:pagina, page_path), user_agent = coalesce(:ua, user_agent)
          WHERE signature = :assinatura
          """)
          .param("assinatura", assinatura).param("pagina", cortar(pagina, 300)).param("ua", cortar(userAgent, 300))
          .update();
      if (atualizadas > 0) {
        if (Boolean.TRUE.equals(estavaResolvido)) whatsapp.avisarErroReaberto(origem, msg, pagina);
        return;
      }
      long distintos = jdbc.sql("SELECT count(*) FROM app_errors").query(Long.class).single();
      if (distintos >= MAX_ERROS_DISTINTOS) return;
      jdbc.sql("""
          INSERT INTO app_errors (origin, signature, message, detail, page_path, user_agent)
          VALUES (:origem, :assinatura, :mensagem, :detalhe, :pagina, :ua)
          ON CONFLICT (signature) DO UPDATE SET occurrences = app_errors.occurrences + 1, last_seen_at = now(), resolved_at = NULL
          """)
          .param("origem", origem).param("assinatura", assinatura).param("mensagem", msg).param("detalhe", det)
          .param("pagina", cortar(pagina, 300)).param("ua", cortar(userAgent, 300))
          .update();
      whatsapp.avisarErroNovo(origem, msg, pagina);
    } catch (RuntimeException falha) {
      logger.warn("Nao foi possivel registrar erro de {}: {}", origem, falha.toString());
    }
  }

  /**
   * Agrupa o mesmo erro mesmo quando detalhes variaveis mudam: numeros (ids, linha:coluna), hashes
   * de chunk do build do Angular (mudam a cada deploy) e query string. Usa a mensagem e so as 3
   * primeiras linhas da pilha — o resto costuma ser framework e so espalharia o agrupamento.
   */
  static String assinatura(String origem, String mensagem, String detalhe) {
    String pilha = detalhe == null ? "" : String.join("\n", detalhe.lines().limit(3).toList());
    String base = origem + "|" + normalizar(mensagem) + "|" + normalizar(pilha);
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(base.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String normalizar(String texto) {
    return texto
        .replaceAll("\\?[^\\s:)]*", "")
        .replaceAll("(chunk|main|polyfills)-[A-Za-z0-9_-]+", "$1")
        .replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}", "<uuid>")
        .replaceAll("\\d+", "#");
  }

  private static String cortar(String texto, int max) {
    if (texto == null || texto.isBlank()) return null;
    return texto.length() > max ? texto.substring(0, max) : texto;
  }

  List<ErroRegistrado> listar(boolean resolvidos) {
    return jdbc.sql("""
        SELECT id, origin, message, detail, page_path, user_agent, occurrences, first_seen_at, last_seen_at
        FROM app_errors
        WHERE (resolved_at IS NOT NULL) = :resolvidos
        ORDER BY last_seen_at DESC
        LIMIT 200
        """)
        .param("resolvidos", resolvidos)
        .query((rs, linha) -> new ErroRegistrado(rs.getLong("id"), rs.getString("origin"), rs.getString("message"),
            rs.getString("detail"), rs.getString("page_path"), rs.getString("user_agent"), rs.getInt("occurrences"),
            rs.getObject("first_seen_at", OffsetDateTime.class), rs.getObject("last_seen_at", OffsetDateTime.class)))
        .list();
  }

  int resolver(long id) {
    return jdbc.sql("UPDATE app_errors SET resolved_at = now() WHERE id = :id AND resolved_at IS NULL").param("id", id).update();
  }

  record ErroRegistrado(long id, String origem, String mensagem, String detalhe, String pagina, String userAgent,
      int ocorrencias, OffsetDateTime primeiraEm, OffsetDateTime ultimaEm) {}
}
