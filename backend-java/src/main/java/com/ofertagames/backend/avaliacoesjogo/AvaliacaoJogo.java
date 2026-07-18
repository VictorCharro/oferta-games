package com.ofertagames.backend.avaliacoesjogo;

import java.time.Instant;

public record AvaliacaoJogo(
    long id,
    String usuarioNome,
    String usuarioAvatarUrl,
    int nota,
    String comentario,
    Instant criadoEm,
    boolean recomenda,
    int util,
    int naoUtil,
    Boolean meuVoto) {}
