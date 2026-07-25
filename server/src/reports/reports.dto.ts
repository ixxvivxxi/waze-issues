import {
  ArrayMinSize,
  IsArray,
  IsIn,
  IsNumber,
  IsObject,
  IsOptional,
  IsString,
  Max,
  MaxLength,
  Min,
  MinLength,
  ValidateNested,
} from 'class-validator';
import { Type } from 'class-transformer';
import type { IssueType, ReportStatus } from './map-report.entity';

const ISSUE_TYPES: IssueType[] = [
  'speed_bump_add',
  'speed_bump_remove',
  'speed_limit',
  'general',
];

/** 0 = end of speed limit (отмена ограничения). */
const SPEED_VALUES = [0, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120];

export class CreateReportDto {
  @IsIn(ISSUE_TYPES)
  issueType!: IssueType;

  @IsNumber()
  @Min(-180)
  @Max(180)
  lon!: number;

  @IsNumber()
  @Min(-90)
  @Max(90)
  lat!: number;

  @IsString()
  @MinLength(1)
  @MaxLength(128)
  reporterNick!: string;

  @IsOptional()
  @IsObject()
  payload?: Record<string, unknown>;

  @IsOptional()
  @IsString()
  @MaxLength(128)
  clientEventId?: string;
}

export class LonLatDto {
  @IsNumber()
  @Min(-180)
  @Max(180)
  lon!: number;

  @IsNumber()
  @Min(-90)
  @Max(90)
  lat!: number;
}

export class TrajectoryDto {
  @IsArray()
  @ArrayMinSize(2)
  @ValidateNested({ each: true })
  @Type(() => LonLatDto)
  points!: LonLatDto[];

  @IsOptional()
  @IsNumber()
  @Min(0)
  @Max(360)
  headingDeg?: number;
}

export class UpdateReportDto {
  @IsOptional()
  @IsString()
  @MaxLength(4000)
  description?: string;

  @IsOptional()
  @IsIn(['pending', 'done', 'dismissed'] as ReportStatus[])
  status?: ReportStatus;
}

export function assertSpeedPayload(
  issueType: IssueType,
  payload: Record<string, unknown> | undefined,
): Record<string, unknown> {
  if (issueType !== 'speed_limit') {
    return payload ?? {};
  }
  const raw = payload?.valueKmh;
  const valueKmh = typeof raw === 'number' ? raw : Number(raw);
  if (!SPEED_VALUES.includes(valueKmh)) {
    throw new Error(
      `speed_limit requires payload.valueKmh in [${SPEED_VALUES.join(', ')}]`,
    );
  }
  return { valueKmh };
}

export function bearingDegrees(
  a: { lon: number; lat: number },
  b: { lon: number; lat: number },
): number {
  const toRad = (d: number) => (d * Math.PI) / 180;
  const φ1 = toRad(a.lat);
  const φ2 = toRad(b.lat);
  const Δλ = toRad(b.lon - a.lon);
  const y = Math.sin(Δλ) * Math.cos(φ2);
  const x =
    Math.cos(φ1) * Math.sin(φ2) -
    Math.sin(φ1) * Math.cos(φ2) * Math.cos(Δλ);
  const θ = Math.atan2(y, x);
  return ((θ * 180) / Math.PI + 360) % 360;
}
