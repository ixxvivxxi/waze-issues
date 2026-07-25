import { MigrationInterface, QueryRunner } from 'typeorm';

export class InitMapReports1753420000000 implements MigrationInterface {
  name = 'InitMapReports1753420000000';

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`CREATE EXTENSION IF NOT EXISTS pgcrypto`);
    await queryRunner.query(`
      CREATE TABLE map_reports (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        issue_type VARCHAR(64) NOT NULL,
        payload JSONB NOT NULL DEFAULT '{}',
        description TEXT,
        reporter_nick VARCHAR(128) NOT NULL,
        lon DOUBLE PRECISION NOT NULL,
        lat DOUBLE PRECISION NOT NULL,
        trajectory JSONB,
        heading_deg DOUBLE PRECISION,
        status VARCHAR(32) NOT NULL DEFAULT 'pending',
        client_event_id VARCHAR(128) UNIQUE,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
      )
    `);
    await queryRunner.query(
      `CREATE INDEX idx_map_reports_status ON map_reports (status)`,
    );
    await queryRunner.query(
      `CREATE INDEX idx_map_reports_lon_lat ON map_reports (lon, lat)`,
    );
    await queryRunner.query(
      `CREATE INDEX idx_map_reports_created_at ON map_reports (created_at DESC)`,
    );
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`DROP TABLE IF EXISTS map_reports`);
  }
}
