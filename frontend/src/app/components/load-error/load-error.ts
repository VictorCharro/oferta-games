import { Component, EventEmitter, Input, Output, inject } from '@angular/core';
import { StatusResposta } from '../../services/status-resposta';

@Component({
  selector: 'app-load-error',
  standalone: false,
  templateUrl: './load-error.html',
  styleUrl: './load-error.scss',
})
export class LoadError {
  @Input() message = 'Não foi possível carregar os dados.';
  @Output() retry = new EventEmitter<void>();

  // Toda pagina que mostra erro de carregamento passa por este componente, entao marcar o 503 aqui
  // cobre Home, Catalogo, Mais vendidos, Gratuitos e Busca de uma vez: no SSR, uma falha da API sai
  // como 503 e a CDN nao a guarda (ver StatusResposta). No navegador nao faz nada.
  constructor() {
    inject(StatusResposta).indisponivel();
  }
}
