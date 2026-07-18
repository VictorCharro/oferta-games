package com.ofertagames.backend.jogos;

import java.util.List;

public record DetalhesJogo(
    String descricao,
    List<String> generos,
    List<String> desenvolvedores,
    List<String> publicadoras,
    String dataLancamento,
    List<String> screenshots,
    String notaReviews,
    Integer reviewsPositivas,
    Integer reviewsNegativas) {}
