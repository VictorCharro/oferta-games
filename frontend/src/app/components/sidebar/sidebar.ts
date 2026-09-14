import { Component } from '@angular/core';
import { Router } from '@angular/router';
import { MenuMobileService } from '../../services/menu-mobile';

@Component({
  selector: 'app-sidebar',
  standalone: false,
  templateUrl: './sidebar.html',
  styleUrl: './sidebar.scss',
})
export class Sidebar {
  constructor(public menuMobile: MenuMobileService, public router: Router) {}
}
