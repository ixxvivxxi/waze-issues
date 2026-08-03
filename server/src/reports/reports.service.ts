import {
  BadRequestException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { MapReportEntity } from './map-report.entity';
import {
  CreateReportDto,
  TrajectoryDto,
  UpdateReportDto,
  assertSpeedPayload,
  bearingDegrees,
  mergeReportPayload,
} from './reports.dto';

export type ReportDto = {
  id: string;
  issueType: string;
  payload: Record<string, unknown>;
  description: string | null;
  reporterNick: string;
  lon: number;
  lat: number;
  trajectory: { lon: number; lat: number }[] | null;
  headingDeg: number | null;
  status: string;
  clientEventId: string | null;
  createdAt: string;
  updatedAt: string;
};

@Injectable()
export class ReportsService {
  constructor(
    @InjectRepository(MapReportEntity)
    private readonly repo: Repository<MapReportEntity>,
  ) {}

  private toDto(row: MapReportEntity): ReportDto {
    return {
      id: row.id,
      issueType: row.issueType,
      payload: row.payload ?? {},
      description: row.description,
      reporterNick: row.reporterNick,
      lon: row.lon,
      lat: row.lat,
      trajectory: row.trajectory,
      headingDeg: row.headingDeg,
      status: row.status,
      clientEventId: row.clientEventId,
      createdAt: row.createdAt.toISOString(),
      updatedAt: row.updatedAt.toISOString(),
    };
  }

  async create(dto: CreateReportDto): Promise<ReportDto> {
    let payload: Record<string, unknown>;
    try {
      payload = assertSpeedPayload(dto.issueType, dto.payload);
    } catch (e) {
      throw new BadRequestException(
        e instanceof Error ? e.message : 'Invalid payload',
      );
    }

    if (dto.clientEventId) {
      const existing = await this.repo.findOne({
        where: { clientEventId: dto.clientEventId },
      });
      if (existing) return this.toDto(existing);
    }

    const row = this.repo.create({
      issueType: dto.issueType,
      payload,
      reporterNick: dto.reporterNick.trim(),
      lon: dto.lon,
      lat: dto.lat,
      clientEventId: dto.clientEventId?.trim() || null,
      status: 'pending',
    });
    const saved = await this.repo.save(row);
    return this.toDto(saved);
  }

  async attachTrajectory(id: string, dto: TrajectoryDto): Promise<ReportDto> {
    const row = await this.repo.findOne({ where: { id } });
    if (!row) throw new NotFoundException('Report not found');

    const points = dto.points.map((p) => ({ lon: p.lon, lat: p.lat }));
    const first = points[0];
    const last = points[points.length - 1];
    const headingDeg =
      dto.headingDeg != null
        ? dto.headingDeg
        : bearingDegrees(first, last);

    row.trajectory = points;
    row.headingDeg = headingDeg;
    const saved = await this.repo.save(row);
    return this.toDto(saved);
  }

  async update(id: string, dto: UpdateReportDto): Promise<ReportDto> {
    const row = await this.repo.findOne({ where: { id } });
    if (!row) throw new NotFoundException('Report not found');
    if (dto.description !== undefined) {
      row.description = dto.description;
    }
    if (dto.status !== undefined) {
      row.status = dto.status;
    }
    if (dto.payload !== undefined) {
      try {
        row.payload = mergeReportPayload(row.issueType, row.payload, dto.payload);
      } catch (e) {
        throw new BadRequestException(
          e instanceof Error ? e.message : 'Invalid payload',
        );
      }
    }
    const saved = await this.repo.save(row);
    return this.toDto(saved);
  }

  async remove(id: string): Promise<{ ok: true }> {
    const row = await this.repo.findOne({ where: { id } });
    if (!row) throw new NotFoundException('Report not found');
    await this.repo.remove(row);
    return { ok: true };
  }

  async bbox(params: {
    minLon: number;
    minLat: number;
    maxLon: number;
    maxLat: number;
    status?: string;
  }): Promise<{ reports: ReportDto[] }> {
    const minLon = Math.min(params.minLon, params.maxLon);
    const maxLon = Math.max(params.minLon, params.maxLon);
    const minLat = Math.min(params.minLat, params.maxLat);
    const maxLat = Math.max(params.minLat, params.maxLat);

    const spanLon = maxLon - minLon;
    const spanLat = maxLat - minLat;
    if (spanLon > 0.35 || spanLat > 0.35) {
      throw new BadRequestException('BBox side must be ≤ 0.35°');
    }

    const status = params.status ?? 'pending';
    const rows = await this.repo
      .createQueryBuilder('r')
      .where('r.lon BETWEEN :minLon AND :maxLon', { minLon, maxLon })
      .andWhere('r.lat BETWEEN :minLat AND :maxLat', { minLat, maxLat })
      .andWhere('r.status = :status', { status })
      .orderBy('r.created_at', 'DESC')
      .take(2000)
      .getMany();

    return { reports: rows.map((r) => this.toDto(r)) };
  }

  async getById(id: string): Promise<ReportDto> {
    if (!id) throw new BadRequestException('id required');
    const row = await this.repo.findOne({ where: { id } });
    if (!row) throw new NotFoundException('Report not found');
    return this.toDto(row);
  }

  async stats(): Promise<{
    totals: {
      reports: number;
      reporters: number;
      pending: number;
      done: number;
      dismissed: number;
      byIssueType: Record<string, number>;
    };
    reporters: Array<{
      nick: string;
      reports: number;
      pending: number;
      done: number;
      dismissed: number;
      firstReport: string;
      lastReport: string;
    }>;
    byDay: Array<{ day: string; count: number }>;
  }> {
    const [totalsRow] = await this.repo.query(`
      SELECT
        COUNT(*)::int AS reports,
        COUNT(DISTINCT reporter_nick)::int AS reporters,
        COUNT(*) FILTER (WHERE status = 'pending')::int AS pending,
        COUNT(*) FILTER (WHERE status = 'done')::int AS done,
        COUNT(*) FILTER (WHERE status = 'dismissed')::int AS dismissed,
        COUNT(*) FILTER (WHERE issue_type = 'speed_limit')::int AS speed_limit,
        COUNT(*) FILTER (WHERE issue_type = 'speed_bump_add')::int AS bump_add,
        COUNT(*) FILTER (WHERE issue_type = 'speed_bump_remove')::int AS bump_remove,
        COUNT(*) FILTER (WHERE issue_type = 'general')::int AS general
      FROM map_reports
    `);
    const reporterRows: Array<Record<string, unknown>> = await this.repo.query(`
      SELECT
        reporter_nick AS nick,
        COUNT(*)::int AS reports,
        COUNT(*) FILTER (WHERE status = 'pending')::int AS pending,
        COUNT(*) FILTER (WHERE status = 'done')::int AS done,
        COUNT(*) FILTER (WHERE status = 'dismissed')::int AS dismissed,
        MIN(created_at)::date::text AS first_report,
        MAX(created_at)::date::text AS last_report
      FROM map_reports
      GROUP BY reporter_nick
      ORDER BY COUNT(*) DESC, reporter_nick ASC
    `);
    const dayRows: Array<Record<string, unknown>> = await this.repo.query(`
      SELECT created_at::date::text AS day, COUNT(*)::int AS count
      FROM map_reports
      GROUP BY created_at::date
      ORDER BY created_at::date ASC
    `);

    return {
      totals: {
        reports: Number(totalsRow.reports),
        reporters: Number(totalsRow.reporters),
        pending: Number(totalsRow.pending),
        done: Number(totalsRow.done),
        dismissed: Number(totalsRow.dismissed),
        byIssueType: {
          speed_limit: Number(totalsRow.speed_limit),
          speed_bump_add: Number(totalsRow.bump_add),
          speed_bump_remove: Number(totalsRow.bump_remove),
          general: Number(totalsRow.general),
        },
      },
      reporters: reporterRows.map((r) => ({
        nick: String(r.nick),
        reports: Number(r.reports),
        pending: Number(r.pending),
        done: Number(r.done),
        dismissed: Number(r.dismissed),
        firstReport: String(r.first_report),
        lastReport: String(r.last_report),
      })),
      byDay: dayRows.map((r) => ({
        day: String(r.day),
        count: Number(r.count),
      })),
    };
  }
}
