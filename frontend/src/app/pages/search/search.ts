import { Component, OnInit, ChangeDetectorRef } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { GameService, GameSummary } from '../../services/game';
import { SeoService } from '../../services/seo';

@Component({
  selector: 'app-search',
  standalone: false,
  templateUrl: './search.html',
  styleUrl: './search.scss',
})
export class Search implements OnInit {
  query = '';
  results: GameSummary[] = [];
  loading = false;
  searched = false;
  error = false;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private gameService: GameService,
    private cdr: ChangeDetectorRef,
    private seo: SeoService
  ) {}

  ngOnInit() {
    this.route.queryParamMap.subscribe(params => {
      const q = params.get('q') || '';
      if (q && q !== this.query) {
        this.query = q;
        this.doSearch();
      }
    });
  }

  doSearch() {
    if (!this.query.trim()) return;
    this.seo.set({
      title: `Busca: ${this.query}`,
      description: `Resultados da busca por "${this.query}" no catálogo de jogos.`,
      path: '/busca',
    });
    this.loading = true;
    this.searched = false;
    this.error = false;
    this.gameService.searchGames(this.query).subscribe({
      next: (data) => { this.results = data; this.loading = false; this.searched = true; this.cdr.detectChanges(); },
      error: () => { this.loading = false; this.searched = true; this.error = true; this.cdr.detectChanges(); }
    });
  }

  onSubmit() {
    this.router.navigate(['/busca'], { queryParams: { q: this.query } });
    this.doSearch();
  }
}
