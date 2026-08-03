import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { MapReportEntity } from './map-report.entity';
import { ReportsController } from './reports.controller';
import { ReportsService } from './reports.service';
import { StatsController } from './stats.controller';

@Module({
  imports: [TypeOrmModule.forFeature([MapReportEntity])],
  controllers: [ReportsController, StatsController],
  providers: [ReportsService],
})
export class ReportsModule {}
