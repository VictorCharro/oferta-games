package com.ofertagames.backend.conexoes;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Conexao da conta Xbox via OAuth da OpenXBL (xbl.io) e sincronizacao da biblioteca.
 *
 * <p>Usa o recurso "Xbox App" da OpenXBL, e nao a API key pessoal — a key pessoal so consulta
 * dados publicos de gamertags usando a conta do dono do site, enquanto o app permite agir em nome
 * de cada usuario. O token por usuario devolvido no {@code /app/claim} fica em
 * {@code xbox_connections.access_token} e e o que autentica as chamadas seguintes.
 *
 * <p>Diferente do Steam ({@link ServicoConexoesSteam}), aqui nao ha tabela de {@code state}: a
 * correlacao com o usuario vem do proprio bearer da sessao, ja que a OpenXBL redireciona de volta
 * pro frontend com o usuario ainda logado.
 *
 * <p><b>Sincronizacao e sempre upsert-only</b> — {@code xbox_library_games} nunca sofre DELETE,
 * pra nunca apagar jogo que o perfil do usuario ja mostrava caso a OpenXBL pare de devolve-lo. A
 * Steam segue a mesma regra hoje (ver {@link ServicoConexoesSteam}).
 *
 * <p>Limitacoes conhecidas: favoritar, link "Ver na loja" e ordem persistida de platinados
 * continuam so para itens Steam.
 */
@Service
public class ServicoConexoesXbox {
  private final RepositorioConexoesXbox conexoes;
  private final ClienteXbl xbl;

  ServicoConexoesXbox(RepositorioConexoesXbox conexoes, ClienteXbl xbl) {
    this.conexoes = conexoes;
    this.xbl = xbl;
  }

  boolean configurada() {
    return xbl.configurada();
  }

  String appKey() {
    if (!xbl.configurada()) throw new XblNaoConfiguradoException();
    return xbl.appKey();
  }

  void concluir(String usuarioId, String code) {
    ClienteXbl.RespostaClaimXbl resposta = xbl.trocarCodigo(code);
    if (resposta == null || resposta.xuid() == null) {
      throw new FalhaAoConectarXboxException(resposta == null ? null : resposta.message());
    }
    conexoes.salvarConexao(usuarioId, resposta.xuid(), resposta.gamertag(), resposta.avatar(), resposta.gamerscore(), resposta.tokenUsuario());
  }

  public StatusConexaoXbox status(String usuarioId) {
    Optional<RepositorioConexoesXbox.ConexaoXbox> conexao = conexoes.buscarConexao(usuarioId);
    if (conexao.isEmpty()) return StatusConexaoXbox.desconectada();
    return new StatusConexaoXbox(
        true,
        conexao.get().gamertag(),
        conexao.get().avatarUrl(),
        conexao.get().gamerscore(),
        conexao.get().ultimoErro());
  }

  void remover(String usuarioId) {
    conexoes.removerConexao(usuarioId);
  }

  /**
   * Sincroniza a biblioteca inteira do usuario em duas chamadas.
   *
   * <p>A biblioteca vem de uma unica chamada ({@code titleHistory}) de proposito: a OpenXBL tem
   * rate limit apertado (60 req / 5 min no free tier) e uma chamada por jogo estouraria na hora.
   * Minutos jogados exigem uma segunda chamada porque nao vem no {@code titleHistory} — mas ela
   * tambem aceita todos os titulos em lote.
   *
   * <p><b>Upsert-only, nunca DELETE:</b> jogo que a OpenXBL parar de devolver (usuario escondeu do
   * historico, por exemplo) continua salvo. E o oposto da estrategia da Steam, e deliberado.
   *
   * @throws ConexaoXboxNaoEncontradaException usuario sem conexao Xbox ou sem token salvo
   */
  void sincronizarBiblioteca(String usuarioId) {
    RepositorioConexoesXbox.ConexaoXbox conexao = conexoes.buscarConexao(usuarioId).orElseThrow(ConexaoXboxNaoEncontradaException::new);
    String token = conexoes.buscarToken(usuarioId).orElseThrow(ConexaoXboxNaoEncontradaException::new);
    List<ClienteXbl.TituloXbl> titulos = xbl.buscarBiblioteca(token);
    List<String> titleIds = titulos.stream().filter(t -> t != null && t.titleId() != null).map(ClienteXbl.TituloXbl::titleId).toList();
    // Minutos jogados vem de uma chamada separada em lote (nao existe no titleHistory).
    java.util.Map<String, Integer> minutos = titleIds.isEmpty() ? java.util.Map.of() : xbl.buscarMinutosJogados(token, conexao.xuid(), titleIds);
    for (ClienteXbl.TituloXbl titulo : titulos) {
      if (titulo == null || titulo.titleId() == null) continue;
      ClienteXbl.AchievementXbl conquistas = titulo.achievement();
      conexoes.upsertJogoBiblioteca(
          usuarioId,
          titulo.titleId(),
          titulo.name(),
          titulo.displayImage(),
          conquistas == null ? 0 : conquistas.currentGamerscore(),
          conquistas == null ? 0 : conquistas.totalGamerscore(),
          conquistas == null ? 0 : conquistas.currentGamerscore(),
          conquistas == null ? 0 : conquistas.totalGamerscore(),
          minutos.getOrDefault(titulo.titleId(), 0),
          titulo.titleHistory() == null ? null : titulo.titleHistory().lastTimePlayed());
    }
    conexoes.marcarBibliotecaSincronizada(usuarioId);
  }

  /**
   * Biblioteca Xbox no mesmo formato da Steam, pro perfil montar a lista combinada.
   *
   * <p>Reaproveita {@link ServicoConexoesSteam.JogoBibliotecaSteam} com {@code plataforma="xbox"}
   * de proposito, pra nao duplicar no frontend toda a logica de biblioteca, platinados e filtro.
   *
   * <p>O {@code titleId} do Xbox e mapeado para o campo {@code appId} apenas como identificador
   * tecnico — <b>nao e um app id da Steam</b>. Como os dois sao numeros indistinguiveis, e por isso
   * que favoritar por {@code appId} continua Steam-only: colidiria entre as plataformas.
   *
   * <p>Titulo cujo id nao cabe em {@code int} (raro) e ignorado, em vez de derrubar a
   * sincronizacao inteira.
   */
  public List<ServicoConexoesSteam.JogoBibliotecaSteam> biblioteca(String usuarioId) {
    return conexoes.listarBiblioteca(usuarioId).stream()
        .map(jogo -> {
          try {
            int appId = Integer.parseInt(jogo.titleId());
            return new ServicoConexoesSteam.JogoBibliotecaSteam(appId, jogo.titulo(), jogo.minutosJogados(), null, jogo.conquistasDesbloqueadas(), jogo.conquistasTotal(), jogo.capaUrl(), null, null, "xbox");
          } catch (NumberFormatException erro) {
            return null;
          }
        })
        .filter(jogo -> jogo != null)
        .toList();
  }

  static class ConexaoXboxNaoEncontradaException extends RuntimeException {}

  public record StatusConexaoXbox(boolean conectada, String gamertag, String avatarUrl, Integer gamerscore, String ultimoErro) {
    static StatusConexaoXbox desconectada() {
      return new StatusConexaoXbox(false, null, null, null, null);
    }
  }

  static class XblNaoConfiguradoException extends RuntimeException {}
  static class FalhaAoConectarXboxException extends RuntimeException {
    FalhaAoConectarXboxException(String mensagem) {
      super(mensagem);
    }
  }
}
