import { Component } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../services/auth';

@Component({
  selector: 'app-login',
  standalone: false,
  templateUrl: './login.html',
  styleUrl: './login.scss',
})
export class Login {
  email = '';
  password = '';
  error = '';
  mode: 'login' | 'register' = 'login';

  constructor(private auth: AuthService, private router: Router) {}

  submit() {
    if (!this.email || !this.password) { this.error = 'Preencha todos os campos.'; return; }
    this.auth.login(this.email, this.password);
    this.router.navigate(['/']);
  }
}
