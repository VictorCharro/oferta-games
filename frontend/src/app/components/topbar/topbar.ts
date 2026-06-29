import { Component } from '@angular/core';
import { Router } from '@angular/router';
import { ThemeService } from '../../services/theme';

@Component({
  selector: 'app-topbar',
  standalone: false,
  templateUrl: './topbar.html',
  styleUrl: './topbar.scss',
})
export class Topbar {
  searchQuery = '';

  constructor(public theme: ThemeService, private router: Router) {}

  onSearch(event: KeyboardEvent) {
    if (event.key === 'Enter' && this.searchQuery.trim()) {
      this.router.navigate(['/busca'], { queryParams: { q: this.searchQuery.trim() } });
    }
  }
}
