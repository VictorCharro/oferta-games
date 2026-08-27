import { Component, Input, ElementRef, ViewChild, AfterViewInit, OnChanges, OnDestroy, HostListener } from '@angular/core';
import { FavoriteGame } from '../../services/favorites';

@Component({
  selector: 'app-monitored-deals-carousel',
  standalone: false,
  templateUrl: './monitored-deals-carousel.html',
  styleUrl: './monitored-deals-carousel.scss',
})
export class MonitoredDealsCarousel implements AfterViewInit, OnChanges, OnDestroy {
  @Input() games: FavoriteGame[] = [];
  @ViewChild('row') rowRef!: ElementRef<HTMLElement>;
  @ViewChild('prevBtn') prevBtnRef!: ElementRef<HTMLElement>;
  @ViewChild('nextBtn') nextBtnRef!: ElementRef<HTMLElement>;

  private observer?: MutationObserver;

  ngAfterViewInit() {
    if (typeof MutationObserver === 'undefined') return;
    const row = this.rowRef.nativeElement;
    this.observer = new MutationObserver(() => this.scheduleUpdate());
    this.observer.observe(row, { childList: true });
    this.scheduleUpdate();
  }

  ngOnChanges() {
    this.scheduleUpdate();
  }

  ngOnDestroy() {
    this.observer?.disconnect();
  }

  @HostListener('window:resize')
  onResize() {
    this.scheduleUpdate();
  }

  onScroll() {
    this.updateScrollState();
  }

  private scheduleUpdate() {
    setTimeout(() => this.updateScrollState());
  }

  private updateScrollState() {
    const row = this.rowRef?.nativeElement;
    if (!row) return;
    const canLeft = row.scrollLeft > 4;
    const canRight = row.scrollLeft < row.scrollWidth - row.clientWidth - 4;
    if (this.prevBtnRef) this.prevBtnRef.nativeElement.style.display = canLeft ? 'flex' : 'none';
    if (this.nextBtnRef) this.nextBtnRef.nativeElement.style.display = canRight ? 'flex' : 'none';
  }

  scroll(dir: number) {
    const row = this.rowRef.nativeElement;
    const delta = dir * row.clientWidth * 0.8;
    const max = row.scrollWidth - row.clientWidth;
    const target = Math.min(Math.max(row.scrollLeft + delta, 0), max);
    this.animateScrollTo(row, target);
  }

  private animateScrollTo(el: HTMLElement, target: number, duration = 400) {
    const start = el.scrollLeft;
    const change = target - start;
    if (change === 0) return;
    const startTime = performance.now();
    const ease = (t: number) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2);
    const step = (now: number) => {
      const progress = Math.min((now - startTime) / duration, 1);
      el.scrollLeft = start + change * ease(progress);
      if (progress < 1) requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  }
}
