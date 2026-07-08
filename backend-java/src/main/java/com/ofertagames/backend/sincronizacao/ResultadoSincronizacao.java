package com.ofertagames.backend.sincronizacao;

public record ResultadoSincronizacao(boolean ok, int synced, boolean hasMore, Integer nextPage, int steamBackfilled) {}
