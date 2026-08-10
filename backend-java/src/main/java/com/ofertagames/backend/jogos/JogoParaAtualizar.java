package com.ofertagames.backend.jogos;

import java.time.Instant;

public record JogoParaAtualizar(
    Long id,
    String titulo,
    String itadId,
    String capaUrl,
    Boolean ehDlc,
    String instantGamingUrl,
    Instant ultimoRefreshManual
) {}
