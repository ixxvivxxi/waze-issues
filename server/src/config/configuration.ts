export interface AppConfig {
  databaseUrl: string;
  port: number;
}

export default (): AppConfig => ({
  databaseUrl: process.env.DATABASE_URL ?? '',
  port: Number(process.env.PORT ?? 3000),
});
