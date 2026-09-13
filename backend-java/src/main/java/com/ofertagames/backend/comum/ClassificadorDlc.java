package com.ofertagames.backend.comum;

import java.util.List;

/**
 * Separa DLC de jogo base, combinando o sinal da Steam com heuristica de titulo.
 *
 * <p>Os dois sinais sao necessarios: a Steam classifica trilha sonora como {@code type: "music"}
 * (nao {@code "dlc"}), entao depender so dela deixava esses itens como jogo base. A mesma lista
 * {@link #TERMOS} alimenta a checagem em Java e a condicao SQL, pra catalogo e sincronizacao nunca
 * divergirem. Sem regex de proposito — ver {@link TermosSql}.
 */
public final class ClassificadorDlc {
  private static final List<String> TERMOS = TermosSql.validar(List.of(
      "dlc",
      "season pass",
      "soundtrack",
      "art book",
      "skin set",
      "skin pack",
      "booster pack",
      "expansion",
      "add-on",
      "monk decipher",
      "demonic weapon pack",
      "foundation boost",
      "arcane boost",
      "lion heart pack",
      "ancient labyrinth"
  ));

  private ClassificadorDlc() {}

  /** Heuristica de titulo apenas — nao conhece {@code games.is_dlc}. Null vira {@code false}. */
  public static boolean pareceDlc(String titulo) {
    return TermosSql.contemAlgum(titulo, TERMOS);
  }

  /**
   * Expressao booleana SQL que decide se a linha e DLC.
   *
   * <p>A heuristica de titulo so entra quando {@code is_dlc IS NULL} (ainda nao classificado pelo
   * job da Steam). Um jogo com {@code is_dlc = false} explicito continua jogo base mesmo que o
   * titulo tenha um dos termos — a classificacao ja feita vence a heuristica.
   *
   * @param alias alias da tabela {@code games} na query; precisa ser literal do proprio codigo,
   *     nunca valor vindo de request (e interpolado direto no SQL)
   */
  public static String condicaoDlcSql(String alias) {
    return "(" + alias + ".is_dlc = true OR (" + alias + ".is_dlc IS NULL AND "
        + TermosSql.contemAlgumSql(alias + ".title", TERMOS) + "))";
  }

  /**
   * Fragmento pronto pra concatenar numa clausula WHERE ja existente — comeca com {@code AND}.
   *
   * <p>Por isso as queries do catalogo abrem com {@code WHERE 1=1}: e o que permite concatenar
   * estes fragmentos em qualquer combinacao sem montar a condicao inicial na mao.
   */
  public static String filtroApenasJogosSql(String alias) {
    return "AND NOT " + condicaoDlcSql(alias);
  }
}
