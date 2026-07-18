package com.ofertagames.backend.avaliacoesjogo;

import java.util.List;

public record RespostaAvaliacoes(ResumoAvaliacoes resumo, AvaliacaoJogo minha, List<AvaliacaoJogo> avaliacoes) {}
