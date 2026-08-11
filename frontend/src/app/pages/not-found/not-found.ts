import { Component, OnInit } from '@angular/core';
import { SeoService } from '../../services/seo';

@Component({
  selector: 'app-not-found',
  standalone: false,
  templateUrl: './not-found.html',
  styleUrl: './not-found.scss',
})
export class NotFound implements OnInit {
  constructor(private seo: SeoService) {}

  ngOnInit() {
    this.seo.set({
      title: 'Página não encontrada',
      description: 'A página que você procura não existe ou foi removida.',
    });
  }
}
