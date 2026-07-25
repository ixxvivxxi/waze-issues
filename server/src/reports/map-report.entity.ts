import {
  Column,
  CreateDateColumn,
  Entity,
  Index,
  PrimaryGeneratedColumn,
  UpdateDateColumn,
} from 'typeorm';

export type IssueType =
  | 'speed_bump_add'
  | 'speed_bump_remove'
  | 'speed_limit';

export type ReportStatus = 'pending' | 'done' | 'dismissed';

export type LonLat = { lon: number; lat: number };

@Entity({ name: 'map_reports' })
export class MapReportEntity {
  @PrimaryGeneratedColumn('uuid')
  id!: string;

  @Column({ name: 'issue_type', type: 'varchar', length: 64 })
  issueType!: IssueType;

  @Column({ type: 'jsonb', default: () => "'{}'" })
  payload!: Record<string, unknown>;

  @Column({ type: 'text', nullable: true })
  description!: string | null;

  @Column({ name: 'reporter_nick', type: 'varchar', length: 128 })
  reporterNick!: string;

  @Column({ type: 'double precision' })
  lon!: number;

  @Column({ type: 'double precision' })
  lat!: number;

  /** WGS84 trail after the tap, used to derive travel direction. */
  @Column({ type: 'jsonb', nullable: true })
  trajectory!: LonLat[] | null;

  @Column({ name: 'heading_deg', type: 'double precision', nullable: true })
  headingDeg!: number | null;

  @Index()
  @Column({ type: 'varchar', length: 32, default: 'pending' })
  status!: ReportStatus;

  @Column({ name: 'client_event_id', type: 'varchar', length: 128, nullable: true, unique: true })
  clientEventId!: string | null;

  @CreateDateColumn({ name: 'created_at', type: 'timestamptz' })
  createdAt!: Date;

  @UpdateDateColumn({ name: 'updated_at', type: 'timestamptz' })
  updatedAt!: Date;
}
