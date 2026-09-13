import { Component, OnInit } from '@angular/core';
import { SeoService } from '../../services/seo';
import { StatusResposta } from '../../services/status-resposta';

@Component({
  selector: 'app-not-found',
  standalone: false,
  templateUrl: './not-found.html',
  styleUrl: './not-found.scss',
})
export class NotFound implements OnInit {
  constructor(private seo: SeoService, private statusResposta: StatusResposta) {}

  ngOnInit() {
    // Rota "**" (caminhos com mais de um segmento que nao existem). Os de um segmento caem em
    // :handle e o perfil publico responde o 404 por la.
    this.statusResposta.naoEncontrado();
    this.seo.naoEncontrado();
  }
}
