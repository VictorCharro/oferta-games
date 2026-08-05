package com.ofertagames.backend.steam;

import java.util.List;

public record DetalhesAplicativoSteam(
    boolean ehDlc,
    String imagemCabecalho,
    String descricaoCurta,
    List<String> generos,
    List<String> desenvolvedores,
    List<String> publicadoras,
    String dataLancamento,
    List<String> screenshots,
    String trailerUrl,
    String trailerThumbnail,
    String sobreCompleto,
    List<DestaqueSteam> destaques,
    List<String> categorias,
    String requisitosMinimos,
    String requisitosRecomendados,
    List<Integer> dlcAppIds) {

  public record DestaqueSteam(String titulo, String texto) {}
}
