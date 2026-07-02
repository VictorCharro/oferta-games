import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';

// Ponte entre o campo de busca do topbar e a página de catálogo, que usa
// o mesmo texto digitado pra filtrar a lista em tempo real.
@Injectable({ providedIn: 'root' })
export class SearchService {
  private _query = new BehaviorSubject<string>('');
  query$ = this._query.asObservable();

  setQuery(q: string) {
    this._query.next(q);
  }
}
