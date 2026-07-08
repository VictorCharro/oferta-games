package com.ofertagames.backend.games;

import java.util.List;

public record GameDetail(
    Long id,
    String slug,
    String title,
    String coverUrl,
    List<OfferDto> offers
) {}
