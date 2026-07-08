package com.ofertagames.backend.itad;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RespostaOfertasItad(Boolean hasMore, Integer nextOffset, List<ItemOfertaItad> list) {}
