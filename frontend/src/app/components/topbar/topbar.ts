import { Component, HostListener, ChangeDetectorRef, OnInit, OnDestroy } from '@angular/core';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { ThemeService } from '../../services/theme';
import { AuthService } from '../../services/auth';

@Component({
  selector: 'app-topbar',
  standalone: false,
  templateUrl: './topbar.html',
  styleUrl: './topbar.scss',
})
export class Topbar implements OnInit, OnDestroy {
  searchQuery = '';
  dropdownOpen = false;
  private sub!: Subscription;

  constructor(public theme: ThemeService, public auth: AuthService, private router: Router, private cdr: ChangeDetectorRef) {}

  ngOnInit() {
    this.sub = this.auth.user$.subscribe(() => this.cdr.detectChanges());
  }

  ngOnDestroy() { this.sub.unsubscribe(); }

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
