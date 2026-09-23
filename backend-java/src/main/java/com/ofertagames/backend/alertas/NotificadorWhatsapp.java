package com.ofertagames.backend.alertas;

import com.ofertagames.backend.comum.LimitePorJanela;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Avisa o dono por WhatsApp (22/09/2026) quando algo quebra sem ninguem olhar o admin: falha de
 * job de coleta e primeira ocorrencia (ou reabertura) de erro de runtime — ver
 * {@code erros.RegistroErros} e {@code sincronizacao.ServicoExecucaoColeta}.
 *
 * <p>Usa o CallMeBot (nao-oficial, gratis, ja usado pelo dono em outro projeto com o mesmo
 * numero): um simples {@code GET} com telefone + texto + apikey, sem conta business nem
 * aprovacao de template. Escolha deliberada: um atraso ocasional de minuto ou uma mensagem
 * perdida em pico nao importam pra um alerta pessoal, e o custo de configurar Twilio/Meta Cloud
 * API (conta business, verificacao, template aprovado) nao se paga aqui.
 *
 * <p><b>Desligado por padrao</b> (telefone/apikey vazios): nunca bloqueia nem derruba quem chama —
 * falha ou timeout do CallMeBot vira so um log. Roda em thread propria (fire-and-forget) pra nunca
 * atrasar o registro do erro nem a resposta do job de coleta.
 *
 * <p><b>Teto de {@value #LIMITE_POR_HORA} mensagens/hora</b> — nao pelo custo (e gratis), mas
 * porque o CallMeBot para de entregar quando recebe rajada de mensagens rapido demais: sem esse
 * freio, um bug que atinge muitos jogadores de uma vez gera dezenas de erros novos em minutos e o
 * proprio aviso se auto-bloqueia bem na hora que mais importa. Acima do teto, so loga.
 */
@Component
public class NotificadorWhatsapp {
  private static final Logger logger = LoggerFactory.getLogger(NotificadorWhatsapp.class);
  private static final String URL_BASE = "https://api.callmebot.com";
  private static final int LIMITE_POR_HORA = 20;
  private static final DateTimeFormatter FORMATO_HORARIO = DateTimeFormatter.ofPattern("dd/MM HH:mm");

  private final RestClient restClient;
  private final String telefone;
  private final String apiKey;
  private final boolean habilitado;
  private final LimitePorJanela limiteHora = new LimitePorJanela(LIMITE_POR_HORA, Duration.ofHours(1));

  NotificadorWhatsapp(
      RestClient.Builder restClientBuilder,
      @Value("${app.alerta.whatsapp.telefone:}") String telefone,
      @Value("${app.alerta.whatsapp.apikey:}") String apiKey) {
    this.restClient = restClientBuilder.baseUrl(URL_BASE).build();
    this.telefone = telefone;
    this.apiKey = apiKey;
    this.habilitado = !telefone.isBlank() && !apiKey.isBlank();
  }

  /** Job de coleta falhou. Sempre avisa — e um evento raro por natureza, nunca gera rajada. */
  public void avisarFalhaDeColeta(String tipo, String resumoErro) {
    enviar("⚠️ Coleta \"" + tipo + "\" falhou\n" + resumoErro + "\n" + agora());
  }

  /** Assinatura de erro nunca vista antes (navegador ou backend). */
  public void avisarErroNovo(String origem, String mensagem, String pagina) {
    enviar("🐞 Erro novo (" + origem + ")\n" + mensagem + contextoPagina(pagina) + "\n" + agora());
  }

  /** Erro que o dono ja tinha marcado como resolvido voltou a acontecer. */
  public void avisarErroReaberto(String origem, String mensagem, String pagina) {
    enviar("🔁 Erro reaberto (" + origem + ")\n" + mensagem + contextoPagina(pagina) + "\n" + agora());
  }

  private static String contextoPagina(String pagina) {
    return pagina == null || pagina.isBlank() ? "" : "\n" + pagina;
  }

  private static String agora() {
    return FORMATO_HORARIO.format(java.time.ZonedDateTime.now(ZoneId.of("America/Sao_Paulo")));
  }

  private void enviar(String texto) {
    if (!habilitado) return;
    if (!limiteHora.tentarConsumir()) {
      logger.warn("Aviso de WhatsApp descartado: teto de {}/hora atingido", LIMITE_POR_HORA);
      return;
    }
    // Thread propria: o CallMeBot e um servico de terceiro sem SLA, e nada aqui pode atrasar o
    // fluxo que chamou (registro de erro, conclusao do job de coleta).
    String mensagem = cortar(texto, 1000);
    Thread.ofVirtual().name("aviso-whatsapp").start(() -> {
      try {
        String resposta = restClient.get()
            .uri(uri -> uri.path("/whatsapp.php")
                .queryParam("phone", telefone)
                .queryParam("apikey", apiKey)
                .queryParam("text", mensagem)
                .build())
            .retrieve()
            .body(String.class);
        if (resposta != null && resposta.toLowerCase().contains("error")) {
          logger.warn("CallMeBot recusou o aviso: {}", cortar(resposta, 200));
        }
      } catch (RuntimeException falha) {
        logger.warn("Falha ao enviar aviso de WhatsApp: {}", falha.toString());
      }
    });
  }

  // CallMeBot nao documenta um limite de tamanho, mas mensagem curta chega mais rapido e sofre
  // menos com a instabilidade do servico; 1000 caracteres sobra pra qualquer stack trace resumido.
  private static String cortar(String texto, int max) {
    return texto.length() > max ? texto.substring(0, max) : texto;
  }
}
