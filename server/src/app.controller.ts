import { Controller, Get, Header } from '@nestjs/common';
import { HOME_PAGE_HTML } from './home.page';
import { STATS_PAGE_HTML } from './stats.page';

@Controller()
export class AppController {
  @Get()
  @Header('Content-Type', 'text/html; charset=utf-8')
  home(): string {
    return HOME_PAGE_HTML;
  }

  @Get('stats')
  @Header('Content-Type', 'text/html; charset=utf-8')
  statsPage(): string {
    return STATS_PAGE_HTML;
  }

  @Get('health')
  health() {
    return { ok: true, service: 'waze-issues' };
  }
}
