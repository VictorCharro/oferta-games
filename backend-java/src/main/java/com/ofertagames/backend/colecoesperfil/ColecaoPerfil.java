package com.ofertagames.backend.colecoesperfil;

import com.ofertagames.backend.favoritosperfil.FavoritoPerfilJogo;
import java.util.List;

// Os jogos reusam FavoritoPerfilJogo: ele ja carrega os campos de card do catalogo e da Steam.
public record ColecaoPerfil(long id, String nome, List<FavoritoPerfilJogo> jogos) {}
