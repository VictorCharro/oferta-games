import { Component } from '@angular/core';
import { Router, NavigationEnd } from '@angular/router';
import { PrimeiraPintura } from './services/primeira-pintura';

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
    });
  }
}
