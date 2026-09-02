import { Component } from '@angular/core';
import { MenuMobileService } from '../../services/menu-mobile';

@Component({
  selector: 'app-sidebar',
  standalone: false,
  templateUrl: './sidebar.html',
  styleUrl: './sidebar.scss',
})
export class Sidebar {
  constructor(public menuMobile: MenuMobileService) {}
}
