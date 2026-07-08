package com.ofertagames.backend.jogos;

public record JogoParaAtualizar(
    Long id,
    String title,
    String itadId,
    String coverUrl,
    Boolean isDlc
) {}
