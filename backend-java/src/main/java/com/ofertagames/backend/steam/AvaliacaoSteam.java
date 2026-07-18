package com.ofertagames.backend.steam;

public record AvaliacaoSteam(
    String id,
    String autorSteamId,
    String autorNome,
    String autorAvatarUrl,
    String texto,
    boolean recomenda,
    Integer votosUteis,
    Integer votosEngracados,
    Integer comentarios,
    Integer minutosJogados,
    String criadaEm,
    String idioma) {}
