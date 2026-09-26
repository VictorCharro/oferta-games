import { Component, inject } from '@angular/core';
import { AsyncPipe, NgClass, NgFor } from '@angular/common';
import { Aviso, AvisosService } from '../../services/avisos';

/**
 * Area fixa onde os avisos de {@link AvisosService} aparecem. Fica no {@code app.html}, fora do
 * {@code <router-outlet>}, pra um aviso disparado logo antes de navegar continuar visivel na
 * pagina seguinte.
 *
 * <p>{@code aria-live="polite"}: leitor de tela anuncia o aviso sem interromper o que estava
 * lendo.
 */
@Component({
  selector: 'app-avisos',
  standalone: true,
  imports: [AsyncPipe, NgClass, NgFor],
  templateUrl: './avisos.html',
  styleUrl: './avisos.scss',
})
export class Avisos {
  readonly servico = inject(AvisosService);

  porId(_: number, aviso: Aviso): number {
    return aviso.id;
  }

  icone(tipo: Aviso['tipo']): string {
    return tipo === 'sucesso' ? '✓' : tipo === 'erro' ? '!' : 'i';
  }
}
