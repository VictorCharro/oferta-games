package com.ofertagames.backend.jogos;

import java.util.List;

public record DetalheJogo(
    Long id,
    String slug,
    String title,
    String coverUrl,
    List<OfertaJogo> offers
) {}
