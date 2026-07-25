# Waze Issues

Phone → server → database tool for flagging Waze map problems while driving (speed bumps, speed limits). Review in WME comes later via a userscript.

| Piece | Path | Role |
|-------|------|------|
| API | [`server/`](server/) | NestJS + Postgres; stores location, trajectory, heading |
| Android | [`android/`](android/) | Split-screen one-tap reporter |
| Deploy | [`deploy/`](deploy/) | Docker Compose on VPS + nginx snippets |

**Production:** `https://waze-issues.ster.by` (Let's Encrypt)  
**APK:** `https://waze-issues.ster.by/app.apk`

## API (requires `X-Api-Key`)

- `POST /api/reports` — create (`issueType`, `lon`, `lat`, `reporterNick`, optional `payload.valueKmh`, `clientEventId`)
- `PATCH /api/reports/:id/trajectory` — `{ points: [{lon,lat},…], headingDeg? }`
- `PATCH /api/reports/:id` — `{ description?`, `status? }`
- `GET /api/reports/bbox?minLon&minLat&maxLon&maxLat&status=pending`
- `GET /` — health `{ ok, service: "waze-issues" }`

Issue types: `speed_bump_add`, `speed_bump_remove`, `speed_limit` (valueKmh ∈ 40,60,70,90,100,110,120).

Shared Postgres on the VPS has **no PostGIS**; coordinates are `lon`/`lat` doubles and `trajectory` JSONB.

## Local server

```bash
cd server
cp .env.example .env   # set DATABASE_URL + API_KEY
npm install
npm run migration:run
npm run start:dev
```

## Manual deploy (first time / until GHA secrets exist)

On the VPS as `ster` (SSH host `myvps-tunnel`):

1. Create DB on shared `main-postgres` (once):

```bash
docker exec -e PGPASSWORD=… main-postgres \
  psql -U ster -d postgres -c "CREATE USER waze_issues WITH PASSWORD '…';"
docker exec -e PGPASSWORD=… main-postgres \
  psql -U ster -d postgres -c "CREATE DATABASE waze_issues OWNER waze_issues;"
```

2. Sync this repo to `~/waze-issues`, fill `deploy/.env.prod` from `.env.prod.example`.

3. `cd ~/waze-issues/deploy && chmod +x deploy.sh && ./deploy.sh`

4. Host nginx + Let's Encrypt for `waze-issues.ster.by` (proxy to local `8095`/`8096`):

```bash
# writes /etc/nginx/sites-available/waze-issues and runs certbot --nginx
~/waze-issues/deploy/install-nginx.sh
```

5. Copy APK to `deploy/public/app.apk`.

Compose binds API/static on **127.0.0.1 only**; public access is via nginx on 443.

## Android

```bash
cd android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

In the app: Settings → nick + API key (same as server `API_KEY`). Use split-screen with Waze.

## GitHub Actions

- [`build-server.yml`](.github/workflows/build-server.yml) — `docker build` only
- [`build-android.yml`](.github/workflows/build-android.yml) — assemble debug APK artifact

**Deploy workflow is deferred** until SSH/registry secrets can be added.

## License

Private / UNLICENSED.
