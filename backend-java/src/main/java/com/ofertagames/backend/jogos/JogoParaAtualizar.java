package com.ofertagames.backend.jogos;

public record JogoParaAtualizar(
    Long id,
    String titulo,
    String itadId,
    String capaUrl,
    Boolean ehDlc,
    String instantGamingUrl
) {}
