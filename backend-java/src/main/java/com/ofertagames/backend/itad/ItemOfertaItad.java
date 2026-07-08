package com.ofertagames.backend.itad;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ItemOfertaItad(String id, String slug, String title, AssetsItad assets, DadosOfertaItad deal) {}
