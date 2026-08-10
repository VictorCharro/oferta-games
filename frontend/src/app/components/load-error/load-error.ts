import { Component, EventEmitter, Input, Output } from '@angular/core';

@Component({
  selector: 'app-load-error',
  standalone: false,
  templateUrl: './load-error.html',
  styleUrl: './load-error.scss',
})
export class LoadError {
  @Input() message = 'Não foi possível carregar os dados.';
  @Output() retry = new EventEmitter<void>();
}
