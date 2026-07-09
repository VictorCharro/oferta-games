package com.ofertagames.backend.sincronizacao;

public record ResultadoSincronizacao(
    boolean ok,
    int synced,
    int skipped,
    boolean hasMore,
    Integer nextPage,
    int steamBackfilled
) {}
