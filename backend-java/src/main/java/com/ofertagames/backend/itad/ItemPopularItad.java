package com.ofertagames.backend.itad;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Item de {@code /stats/most-popular/v1}: {@code position} 1 = mais popular. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ItemPopularItad(int position, String id, String slug, String title) {}
