import { Injectable } from '@angular/core';
import { BehaviorSubject, firstValueFrom } from 'rxjs';
import { HttpClient } from '@angular/common/http';
import { supabase } from './supabase';
import { AuthService } from './auth';
import { URL_API } from '../configuracao/url-api';

export interface PriceNotification {
  id: number;
  slug: string;
  titulo: string;
  precoAnterior: number;
  precoAtual: number;
  loja: string | null;
  /** 'meta_atingida' = o preço cruzou a meta definida pelo usuário; 'queda' = alerta comum. */
  tipo: 'queda' | 'meta_atingida';
  lida: boolean;
  criadaEm: string;
}

@Injectable({ providedIn: 'root' })
export class NotificationsService {
  private readonly api = `${URL_API}/notifications`;
  private readonly listSubject = new BehaviorSubject<PriceNotification[]>([]);
  readonly list$ = this.listSubject.asObservable();

  constructor(private http: HttpClient, auth: AuthService) {
    auth.user$.subscribe(user => user ? this.load() : this.listSubject.next([]));
  }

  get notifications(): PriceNotification[] { return this.listSubject.value; }
  get unreadCount(): number { return this.notifications.filter(notification => !notification.lida).length; }

  async load() {
    const headers = await this.headers();
    if (!headers) { this.listSubject.next([]); return; }
    try {
      this.listSubject.next(await firstValueFrom(this.http.get<PriceNotification[]>(this.api, { headers })));
    } catch {
      this.listSubject.next([]);
    }
  }

  async markRead(id: number) {
    const headers = await this.headers();
    if (!headers) return;
    await firstValueFrom(this.http.patch(`${this.api}/${id}/read`, {}, { headers }));
    this.listSubject.next(this.notifications.map(notification => notification.id === id ? { ...notification, lida: true } : notification));
  }

  async markAllRead() {
    const headers = await this.headers();
    if (!headers) return;
    await firstValueFrom(this.http.patch(`${this.api}/read-all`, {}, { headers }));
    this.listSubject.next(this.notifications.map(notification => ({ ...notification, lida: true })));
  }

  async remove(id: number) {
    const headers = await this.headers();
    if (!headers) return;
    await firstValueFrom(this.http.delete(`${this.api}/${id}`, { headers }));
    this.listSubject.next(this.notifications.filter(notification => notification.id !== id));
  }

  private async headers(): Promise<{ Authorization: string } | null> {
    const { data } = await supabase.auth.getSession();
    return data.session?.access_token ? { Authorization: `Bearer ${data.session.access_token}` } : null;
  }
}
