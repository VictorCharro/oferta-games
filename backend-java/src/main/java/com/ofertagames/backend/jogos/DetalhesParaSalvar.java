package com.ofertagames.backend.jogos;

import java.util.List;

public record DetalhesParaSalvar(
    long jogoId,
    String descricaoCurta,
    List<String> generos,
    List<String> desenvolvedores,
    List<String> publicadoras,
    String dataLancamento,
    List<String> screenshots,
    String notaReviews,
    Integer reviewsPositivas,
    Integer reviewsNegativas,
    String trailerUrl,
    String trailerThumbnail,
    String sobreCompleto,
    List<DetalhesJogo.DestaqueJogo> destaques,
    List<String> categorias,
    String requisitosMinimos,
    String requisitosRecomendados) {}
