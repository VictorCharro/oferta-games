package com.ofertagames.backend.contato;

import com.ofertagames.backend.autenticacao.Administradores;
import com.ofertagames.backend.autenticacao.ServicoAutenticacao;
import com.ofertagames.backend.comum.LimitePorJanela;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fale conosco: qualquer visitante manda elogio, sugestao, problema ou denuncia; o admin le em
 * /admin/coleta.
 *
 * <p>Diferente da denuncia de perfil, NAO exige login: quem nao consegue entrar e quem mais precisa
 * relatar. Freios contra spam, do mais fino ao mais grosso: campo isca (bot que preenche tudo e
 * descartado em silencio), {@value #MENSAGENS_POR_DIA_CONTA} por conta por dia, teto global de
 * {@value #TETO_ANONIMAS_POR_HORA} anonimas por hora e o limite de escrita por IP do
 * {@code FiltroLimiteRequisicoes}.
 */
@RestController
public class ControladorContato {
  private static final Logger logger = LoggerFactory.getLogger(ControladorContato.class);

  static final int MENSAGENS_POR_DIA_CONTA = 10;
  static final int TETO_ANONIMAS_POR_HORA = 30;
  static final Set<String> TIPOS = Set.of("elogio", "sugestao", "problema", "denuncia", "outro");
  private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

  private final ServicoAutenticacao autenticacao;
  private final Administradores administradores;
  private final RepositorioContato contato;
  private final LimitePorJanela anonimas = new LimitePorJanela(TETO_ANONIMAS_POR_HORA, Duration.ofHours(1));

  ControladorContato(ServicoAutenticacao autenticacao, Administradores administradores, RepositorioContato contato) {
    this.autenticacao = autenticacao;
    this.administradores = administradores;
    this.contato = contato;
  }

  @PostMapping("/api/contato")
  ResponseEntity<Void> enviar(@RequestHeader(value = "Authorization", required = false) String autorizacao,
      @RequestBody EntradaContato entrada) {
    if (entrada == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mensagem vazia");
    // Campo isca, invisivel pra pessoas: responde como sucesso pro bot nao aprender a desviar.
    if (entrada.site() != null && !entrada.site().isBlank()) return ResponseEntity.accepted().build();

    MensagemValidada mensagem = validar(entrada);
    Optional<String> usuario = autenticacao.buscarUsuarioPeloCabecalho(autorizacao);
    if (usuario.isPresent()) {
      if (contato.contarDoUsuarioNasUltimas24h(usuario.get()) >= MENSAGENS_POR_DIA_CONTA) {
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Você atingiu o limite de mensagens por hoje. Tente amanhã.");
      }
    } else if (!anonimas.tentarConsumir()) {
      logger.warn("Teto de mensagens anonimas do Fale conosco atingido");
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Muitas mensagens agora. Tente de novo mais tarde ou entre na sua conta.");
    }
    contato.registrar(mensagem.tipo(), mensagem.texto(), mensagem.email(), usuario.orElse(null), mensagem.pagina());
    logger.info("Mensagem de contato registrada (tipo {}, {})", mensagem.tipo(), usuario.isPresent() ? "logado" : "anonimo");
    return ResponseEntity.accepted().build();
  }

  static MensagemValidada validar(EntradaContato entrada) {
    String tipo = entrada.tipo() == null ? "" : entrada.tipo().trim().toLowerCase(Locale.ROOT);
    if (!TIPOS.contains(tipo)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha o tipo da mensagem");
    String texto = entrada.mensagem() == null ? "" : entrada.mensagem().trim();
    if (texto.length() < 10 || texto.length() > 2000) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escreva a mensagem em 10 a 2000 caracteres");
    }
    String email = entrada.email() == null ? "" : entrada.email().trim();
    if (email.isEmpty()) {
      email = null;
    } else if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "E-mail inválido. Deixe em branco se não quiser resposta.");
    }
    // So caminho interno: evita guardar URL externa ou query string com dado pessoal.
    String pagina = entrada.pagina() == null ? "" : entrada.pagina().trim();
    int corte = pagina.indexOf('?');
    if (corte >= 0) pagina = pagina.substring(0, corte);
    if (!pagina.startsWith("/") || pagina.startsWith("//") || pagina.length() > 300) pagina = null;
    return new MensagemValidada(tipo, texto, email, pagina);
  }

  /** Mensagens que a pessoa mandou logada, com as respostas do admin (entregues no site). */
  @GetMapping("/api/contato/minhas")
  List<RepositorioContato.MinhaMensagem> minhas(@RequestHeader(value = "Authorization", required = false) String autorizacao) {
    return contato.listarDoUsuario(exigirUsuario(autorizacao));
  }

  @PostMapping("/api/contato/minhas/respostas-lidas")
  ResponseEntity<Void> marcarRespostasLidas(@RequestHeader(value = "Authorization", required = false) String autorizacao) {
    contato.marcarRespostasLidas(exigirUsuario(autorizacao));
    return ResponseEntity.noContent().build();
  }

  private String exigirUsuario(String autorizacao) {
    return autenticacao.buscarUsuarioPeloCabecalho(autorizacao)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Entre na sua conta"));
  }

  @PostMapping("/api/admin/contato/{id}/responder")
  ResponseEntity<Void> responder(@PathVariable long id, @RequestHeader(value = "Authorization", required = false) String autorizacao,
      @RequestBody EntradaResposta entrada) {
    administradores.exigir(autorizacao);
    String resposta = entrada == null || entrada.resposta() == null ? "" : entrada.resposta().trim();
    if (resposta.isEmpty() || resposta.length() > 2000) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escreva a resposta em até 2000 caracteres");
    }
    if (contato.responder(id, resposta) == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mensagem não encontrada ou enviada sem conta (não tem pra quem responder)");
    }
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/api/admin/contato")
  List<RepositorioContato.MensagemAdmin> listar(@RequestHeader(value = "Authorization", required = false) String autorizacao,
      @RequestParam(defaultValue = "false") boolean lidas) {
    administradores.exigir(autorizacao);
    return contato.listar(lidas);
  }

  @PostMapping("/api/admin/contato/{id}/resolver")
  ResponseEntity<Void> resolver(@PathVariable long id, @RequestHeader(value = "Authorization", required = false) String autorizacao) {
    administradores.exigir(autorizacao);
    contato.resolver(id);
    return ResponseEntity.noContent().build();
  }

  record EntradaContato(String tipo, String mensagem, String email, String pagina, String site) {}
  record EntradaResposta(String resposta) {}
  record MensagemValidada(String tipo, String texto, String email, String pagina) {}
}
