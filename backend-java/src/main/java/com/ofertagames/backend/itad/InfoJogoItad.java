package com.ofertagames.backend.itad;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Resposta de {@code /games/info/v2}: so os campos que o catalogo usa. {@code type}: "game", "dlc"... */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InfoJogoItad(String id, String slug, String title, String type, AssetsItad assets) {}
