export interface AppConfig {
  databaseUrl: string;
  apiKey: string;
  port: number;
}

export default (): AppConfig => ({
  databaseUrl: process.env.DATABASE_URL ?? '',
  apiKey: process.env.API_KEY ?? '',
  port: Number(process.env.PORT ?? 3000),
});
