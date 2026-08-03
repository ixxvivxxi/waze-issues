import { Controller, Get } from '@nestjs/common';
import { ReportsService } from './reports.service';

@Controller('api/stats')
export class StatsController {
  constructor(private readonly reports: ReportsService) {}

  @Get()
  stats() {
    return this.reports.stats();
  }
}
