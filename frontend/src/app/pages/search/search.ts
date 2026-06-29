import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { GameService, GameSummary } from '../../services/game';

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

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private gameService: GameService
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
    this.loading = true;
    this.searched = false;
    this.gameService.searchGames(this.query).subscribe({
      next: (data) => { this.results = data; this.loading = false; this.searched = true; },
      error: () => { this.loading = false; this.searched = true; }
    });
  }

  onSubmit() {
    this.router.navigate(['/busca'], { queryParams: { q: this.query } });
    this.doSearch();
  }
}
