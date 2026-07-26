import { Controller, Get, Header } from '@nestjs/common';
import { HOME_PAGE_HTML } from './home.page';

@Controller()
export class AppController {
  @Get()
  @Header('Content-Type', 'text/html; charset=utf-8')
  home(): string {
    return HOME_PAGE_HTML;
  }

  @Get('health')
  health() {
    return { ok: true, service: 'waze-issues' };
  }
}
