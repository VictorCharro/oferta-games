package com.ofertagames.backend.autenticacao;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Quem pode usar as rotas {@code /api/admin/**}.
 *
 * <p>Era um UID fixo em {@code ControladorAdministracao}: trocar ou adicionar admin exigia commit e
 * deploy (issue #30). Agora vem so de {@code ADMIN_USER_IDS} (lista separada por virgula). Sem padrao
 * no codigo desde 18/09/2026, quando o repositorio foi preparado pra ficar publico: variavel vazia =
 * ninguem e admin (toda rota de admin responde 403), nunca um UID embutido.
 */
@Component
public class Administradores {
  private final ServicoAutenticacao autenticacao;
  private final Set<String> ids;

  Administradores(ServicoAutenticacao autenticacao,
      @Value("${app.admin.user-ids:}") String ids) {
    this.autenticacao = autenticacao;
    this.ids = Arrays.stream(ids.split(","))
        .map(String::trim)
        .filter(id -> !id.isBlank())
        .collect(Collectors.toUnmodifiableSet());
  }

  /** 401 sem sessao valida, 403 com sessao de quem nao e admin. Devolve o id do admin. */
  public String exigir(String cabecalhoAutorizacao) {
    String usuarioId = autenticacao.buscarUsuarioPeloCabecalho(cabecalhoAutorizacao)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    if (!ids.contains(usuarioId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    return usuarioId;
  }
}
