import { Component } from '@angular/core';
import { Router, NavigationEnd, NavigationError } from '@angular/router';
import { PrimeiraPintura } from './services/primeira-pintura';
import { ehChunkDesatualizado, recarregarNaVersaoNova } from './configuracao/versao-nova';

@Component({
  selector: 'app-root',
  templateUrl: './app.html',
  standalone: false,
  styleUrl: './app.scss'
})
export class App {
  showShell = true;

  // PrimeiraPintura injetado aqui so pra nascer junto com a aplicacao (ver o servico).
  constructor(private router: Router, _pintura: PrimeiraPintura) {
    this.router.events.subscribe(e => {
      if (e instanceof NavigationEnd) {
        const noShell = ['/login'];
        this.showShell = !noShell.some(p => e.urlAfterRedirects.startsWith(p));
      }
      // Aba aberta de antes de um deploy pedindo chunk que nao existe mais: recarrega ja no
      // destino, que vem com a versao nova (ver configuracao/versao-nova.ts). So no navegador.
      if (e instanceof NavigationError && typeof window !== 'undefined' && ehChunkDesatualizado(e.error)) {
        recarregarNaVersaoNova(e.url);
      }
    });
  }
}
