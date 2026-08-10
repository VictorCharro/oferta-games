import { NO_ERRORS_SCHEMA } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, RouterModule } from '@angular/router';
import { App } from './app';

// NO_ERRORS_SCHEMA: testamos a lógica de App (mostrar/esconder a shell conforme a rota), não o
// conteúdo de app-sidebar/app-topbar, então não precisamos declará-los aqui.
describe('App', () => {
  let router: Router;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RouterModule.forRoot([{ path: '**', component: App }])],
      declarations: [App],
      schemas: [NO_ERRORS_SCHEMA],
    }).compileComponents();
    router = TestBed.inject(Router);
  });

  it('cria o componente', () => {
    const fixture = TestBed.createComponent(App);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('mostra a shell (sidebar/topbar) por padrão', () => {
    const fixture = TestBed.createComponent(App);
    expect(fixture.componentInstance.showShell).toBe(true);
  });

  it('esconde a shell na rota /login', async () => {
    const fixture = TestBed.createComponent(App);
    await router.navigateByUrl('/login');
    expect(fixture.componentInstance.showShell).toBe(false);
  });

  it('volta a mostrar a shell fora de /login', async () => {
    const fixture = TestBed.createComponent(App);
    await router.navigateByUrl('/login');
    await router.navigateByUrl('/catalogo');
    expect(fixture.componentInstance.showShell).toBe(true);
  });
});
