import { Component, HostListener } from '@angular/core';
import { Router } from '@angular/router';
import { ThemeService } from '../../services/theme';
import { AuthService } from '../../services/auth';

@Component({
  selector: 'app-topbar',
  standalone: false,
  templateUrl: './topbar.html',
  styleUrl: './topbar.scss',
})
export class Topbar {
  searchQuery = '';
  dropdownOpen = false;

  constructor(public theme: ThemeService, public auth: AuthService, private router: Router) {}

  onSearch(event: KeyboardEvent) {
    if (event.key === 'Enter' && this.searchQuery.trim()) {
      this.router.navigate(['/busca'], { queryParams: { q: this.searchQuery.trim() } });
    }
  }

  toggleDropdown() { this.dropdownOpen = !this.dropdownOpen; }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent) {
    const target = event.target as HTMLElement;
    if (!target.closest('.user-menu')) this.dropdownOpen = false;
  }

  logout() {
    this.dropdownOpen = false;
    this.auth.logout();
  }
}
