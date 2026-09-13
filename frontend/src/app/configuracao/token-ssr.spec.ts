import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { TOKEN_SSR_API, tokenSsrInterceptor } from './token-ssr';
import { URL_API } from './url-api';

describe('tokenSsrInterceptor', () => {
  function configurar(token?: string) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([tokenSsrInterceptor])),
        provideHttpClientTesting(),
        ...(token === undefined ? [] : [{ provide: TOKEN_SSR_API, useValue: token }]),
      ],
    });
    return { http: TestBed.inject(HttpClient), controle: TestBed.inject(HttpTestingController) };
  }

  it('manda o token só pra nossa API quando está no servidor', () => {
    const { http, controle } = configurar('segredo');
    http.get(`${URL_API}/games`).subscribe();
    http.get('https://api.isthereanydeal.com/x').subscribe();

    expect(controle.expectOne(`${URL_API}/games`).request.headers.get('X-SSR-Token')).toBe('segredo');
    expect(controle.expectOne('https://api.isthereanydeal.com/x').request.headers.has('X-SSR-Token')).toBe(false);
    controle.verify();
  });

  it('não manda nada no navegador (token não provido) nem com token vazio', () => {
    const { http, controle } = configurar();
    http.get(`${URL_API}/games`).subscribe();
    expect(controle.expectOne(`${URL_API}/games`).request.headers.has('X-SSR-Token')).toBe(false);
    controle.verify();
  });
});
