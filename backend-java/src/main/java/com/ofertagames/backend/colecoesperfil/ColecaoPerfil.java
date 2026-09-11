package com.ofertagames.backend.colecoesperfil;

import com.ofertagames.backend.favoritosperfil.FavoritoPerfilJogo;
import java.util.List;

// Os jogos reusam FavoritoPerfilJogo: ele ja carrega os campos de card do catalogo e da Steam.
// origemSistema: true pra colecoes geridas automaticamente (hoje so a wishlist da Steam) - o
// frontend usa isso pra esconder os controles de editar/excluir/adicionar/remover item.
public record ColecaoPerfil(long id, String nome, boolean origemSistema, List<FavoritoPerfilJogo> jogos) {}
