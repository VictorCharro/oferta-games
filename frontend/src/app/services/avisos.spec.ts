import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { vi } from 'vitest';
import { AvisosService } from './avisos';

describe('AvisosService', () => {
  let avisos: AvisosService;
  const naTela = async () => firstValueFrom(avisos.avisos$);

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({});
    avisos = TestBed.inject(AvisosService);
  });

  afterEach(() => vi.useRealTimers());

  it('mostra no máximo 3 avisos, descartando o mais antigo', async () => {
    avisos.info('1'); avisos.info('2'); avisos.info('3'); avisos.info('4');
    expect((await naTela()).map(a => a.texto)).toEqual(['2', '3', '4']);
  });

  it('o mesmo texto não empilha: substitui o que já estava na tela', async () => {
    avisos.sucesso('Jogo monitorado');
    avisos.sucesso('Jogo monitorado');
    expect((await naTela()).length).toBe(1);
  });

  it('some sozinho: marca saindo (animação de saída) e só então sai da lista', async () => {
    avisos.sucesso('Salvo');
    vi.advanceTimersByTime(3500);
    const saindo = await naTela();
    expect(saindo.length).toBe(1);
    expect(saindo[0].saindo).toBe(true);

    vi.advanceTimersByTime(200);
    expect((await naTela()).length).toBe(0);
  });

  it('erro fica mais tempo na tela que sucesso', async () => {
    avisos.erro('Falhou');
    vi.advanceTimersByTime(3500);
    expect((await naTela())[0].saindo).toBe(false);
  });
});
