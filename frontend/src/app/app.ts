import { Component } from '@angular/core';
import { Router, NavigationEnd } from '@angular/router';

@Component({
  selector: 'app-root',
  templateUrl: './app.html',
  standalone: false,
  styleUrl: './app.scss'
})
export class App {
  showShell = true;

  constructor(private router: Router) {
    this.router.events.subscribe(e => {
      if (e instanceof NavigationEnd) {
        const noShell = ['/login'];
        this.showShell = !noShell.some(p => e.urlAfterRedirects.startsWith(p));
      }
    });
  }
}
