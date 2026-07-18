package com.ofertagames.backend.steam;

import java.util.List;

public record RespostaAvaliacoesSteam(String steamAppId, List<AvaliacaoSteam> avaliacoes) {}
