import {
  Body,
  Controller,
  Delete,
  Get,
  Param,
  ParseUUIDPipe,
  Patch,
  Post,
  Query,
} from '@nestjs/common';
import {
  CreateReportDto,
  TrajectoryDto,
  UpdateReportDto,
} from './reports.dto';
import { ReportsService } from './reports.service';

@Controller('api/reports')
export class ReportsController {
  constructor(private readonly reports: ReportsService) {}

  @Post()
  create(@Body() body: CreateReportDto) {
    return this.reports.create(body);
  }

  @Get('bbox')
  bbox(
    @Query('minLon') minLon: string,
    @Query('minLat') minLat: string,
    @Query('maxLon') maxLon: string,
    @Query('maxLat') maxLat: string,
    @Query('status') status?: string,
  ) {
    return this.reports.bbox({
      minLon: Number(minLon),
      minLat: Number(minLat),
      maxLon: Number(maxLon),
      maxLat: Number(maxLat),
      status,
    });
  }

  @Get(':id')
  getOne(@Param('id', ParseUUIDPipe) id: string) {
    return this.reports.getById(id);
  }

  @Patch(':id/trajectory')
  trajectory(
    @Param('id', ParseUUIDPipe) id: string,
    @Body() body: TrajectoryDto,
  ) {
    return this.reports.attachTrajectory(id, body);
  }

  @Patch(':id')
  update(
    @Param('id', ParseUUIDPipe) id: string,
    @Body() body: UpdateReportDto,
  ) {
    return this.reports.update(id, body);
  }

  @Delete(':id')
  remove(@Param('id', ParseUUIDPipe) id: string) {
    return this.reports.remove(id);
  }
}
