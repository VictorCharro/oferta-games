package com.ofertagames.backend.source.itad;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ItadGame(
        String id,
        String slug,
        String title,
        @JsonProperty("assets") ItadAssets assets
) {
    public record ItadAssets(String banner400) {}
}
