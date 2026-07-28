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

/** Applicability length in meters; 0 = until signs (unlimited). */
const LENGTH_VALUES: number[] = Array.from({ length: 21 }, (_, i) => i * 50);

export function parseLengthM(raw: unknown): number | undefined {
  if (raw === undefined || raw === null) return undefined;
  const n = typeof raw === 'number' ? raw : Number(raw);
  if (!LENGTH_VALUES.includes(n)) {
    throw new Error(
      `payload.lengthM must be in [${LENGTH_VALUES.join(', ')}] (0 = until signs)`,
    );
  }
  return n;
}

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

  /** Partial payload merge (e.g. lengthM for speed_limit). */
  @IsOptional()
  @IsObject()
  payload?: Record<string, unknown>;
}

function attachAccuracyM(
  payload: Record<string, unknown> | undefined,
  out: Record<string, unknown>,
): Record<string, unknown> {
  const acc = payload?.accuracyM;
  if (typeof acc === 'number' && Number.isFinite(acc) && acc >= 0) {
    out.accuracyM = acc;
  }
  return out;
}

export function assertSpeedPayload(
  issueType: IssueType,
  payload: Record<string, unknown> | undefined,
): Record<string, unknown> {
  if (issueType !== 'speed_limit') {
    const base = { ...(payload ?? {}) };
    return attachAccuracyM(payload, base);
  }
  const raw = payload?.valueKmh;
  const valueKmh = typeof raw === 'number' ? raw : Number(raw);
  if (!SPEED_VALUES.includes(valueKmh)) {
    throw new Error(
      `speed_limit requires payload.valueKmh in [${SPEED_VALUES.join(', ')}]`,
    );
  }
  const out: Record<string, unknown> = { valueKmh };
  // End-of-limit is a point — length does not apply.
  if (valueKmh !== 0) {
    const lengthM = parseLengthM(payload?.lengthM);
    out.lengthM = lengthM ?? 0;
  }
  return attachAccuracyM(payload, out);
}

/** Merge PATCH payload into an existing speed_limit / other report payload. */
export function mergeReportPayload(
  issueType: IssueType,
  existing: Record<string, unknown> | null | undefined,
  patch: Record<string, unknown>,
): Record<string, unknown> {
  const base = { ...(existing ?? {}) };
  if (issueType !== 'speed_limit') {
    return attachAccuracyM(patch, { ...base, ...patch });
  }
  const valueKmhRaw = patch.valueKmh !== undefined ? patch.valueKmh : base.valueKmh;
  const valueKmh =
    typeof valueKmhRaw === 'number' ? valueKmhRaw : Number(valueKmhRaw);
  if (!SPEED_VALUES.includes(valueKmh)) {
    throw new Error(
      `speed_limit requires payload.valueKmh in [${SPEED_VALUES.join(', ')}]`,
    );
  }
  const out: Record<string, unknown> = { ...base, valueKmh };
  if (valueKmh === 0) {
    delete out.lengthM;
  } else if (patch.lengthM !== undefined) {
    out.lengthM = parseLengthM(patch.lengthM) ?? 0;
  } else if (typeof out.lengthM !== 'number') {
    out.lengthM = 0;
  } else {
    out.lengthM = parseLengthM(out.lengthM) ?? 0;
  }
  return attachAccuracyM(patch, out);
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
