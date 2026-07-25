import { DataSource } from 'typeorm';
import * as dotenv from 'dotenv';
import { MapReportEntity } from './reports/map-report.entity';
import { InitMapReports1753420000000 } from './migrations/1753420000000-InitMapReports';

dotenv.config();

export default new DataSource({
  type: 'postgres',
  url: process.env.DATABASE_URL,
  entities: [MapReportEntity],
  migrations: [InitMapReports1753420000000],
  synchronize: false,
  logging: false,
});
