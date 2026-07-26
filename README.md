# Waze Issues

Phone → server → database tool for flagging Waze map problems while driving (speed bumps, speed limits). Review in WME via a userscript.

| Piece | Path | Role |
|-------|------|------|
| API | [`server/`](server/) | NestJS + Postgres; stores location, trajectory, heading |
| Android | [`android/`](android/) | Split-screen one-tap reporter |
| Deploy | [`deploy/`](deploy/) | Docker Compose on VPS + nginx snippets |

**Production API:** `https://waze-issues.ster.by` (configurable in the app; domain may change)  
**APK / updates:** [GitHub Releases `android-latest`](https://github.com/ixxvivxxi/waze-issues/releases/tag/android-latest)  
**Userscript:** [raw on GitHub](https://raw.githubusercontent.com/ixxvivxxi/waze-issues/main/wme-waze-issues.user.js)  
**Image:** `ghcr.io/ixxvivxxi/waze-issues:api`

## API (open for trusted users — no API key)

- `POST /api/reports` — create (`issueType`, `lon`, `lat`, `reporterNick`, optional `payload.valueKmh`, `clientEventId`)
- `PATCH /api/reports/:id/trajectory` — `{ points: [{lon,lat},…], headingDeg? }`
- `PATCH /api/reports/:id` — `{ description?`, `status? }`
- `GET /api/reports/bbox?minLon&minLat&maxLon&maxLat&status=pending`
- `GET /` — health `{ ok, service: "waze-issues" }`

Issue types: `speed_bump_add`, `speed_bump_remove`, `speed_limit` (valueKmh ∈ 0,20,30,40,50,60,70,80,90,100,110,120; `0` = end of limit), `general`.

Shared Postgres on the VPS has **no PostGIS**; coordinates are `lon`/`lat` doubles and `trajectory` JSONB.

## Local server

```bash
cd server
cp .env.example .env   # set DATABASE_URL
npm install
npm run migration:run
npm run start:dev
```

## Deploy (GitHub Actions → GHCR → VPS)

On push to `main` (server/deploy paths) or `workflow_dispatch`:

1. Build & push `ghcr.io/ixxvivxxi/waze-issues:api` (+ `:api-<sha>`)
2. SSH to VPS → `~/waze-issues/deploy/./deploy.sh` (pull + up)

### GitHub secrets (Environment `production`)

| Secret | Purpose |
|--------|---------|
| `DEPLOY_HOST` | VPS host (e.g. `95.128.71.94` or `myvps` hostname) |
| `DEPLOY_USER` | SSH user (`ster`) |
| `DEPLOY_SSH_KEY` | Private SSH key PEM for that user |

`GITHUB_TOKEN` is built-in (`packages: write`) — no custom registry password needed.

### One-time VPS setup

1. Create DB on shared `main-postgres` (once).
2. Keep a thin checkout at `~/waze-issues` with at least `deploy/` (compose + `deploy.sh` + `public/`).
3. `cp deploy/.env.prod.example deploy/.env.prod` and set `DATABASE_URL`. Remove any old `API_KEY`. Set:

```bash
WAZE_ISSUES_IMAGE=ghcr.io/ixxvivxxi/waze-issues:api
```

4. If the GHCR package is **private**, once on the VPS:

```bash
echo "$GHCR_TOKEN" | docker login ghcr.io -u ixxvivxxi --password-stdin
```

(Or make the package public in GitHub → Packages.)

5. Host nginx + Let's Encrypt for `waze-issues.ster.by` (proxy to `8095`/`8096`).

Manual deploy: `cd ~/waze-issues/deploy && ./deploy.sh`

## Android

Default API base: `https://waze-issues.ster.by` (change anytime in **Settings** — updates do not use this domain).

In-app update check reads:
`https://github.com/ixxvivxxi/waze-issues/releases/download/android-latest/version.json`

```bash
./android/build-apk.sh            # release APK → ./publish-apk/
./android/build-apk.sh --publish  # upload to GitHub Releases (needs gh auth)
```

Bump `versionCode` in `android/app/build.gradle.kts` on each published build.

## WME userscript

Install / update (raw GitHub):  
https://raw.githubusercontent.com/ixxvivxxi/waze-issues/main/wme-waze-issues.user.js

Tampermonkey uses `@updateURL` / `@downloadURL` pointing at that same file.

1. Open WME → sidebar **Drive reports**
2. API base `https://waze-issues.ster.by`
3. Enable **Show pending reports**
4. Click a marker → **Done** / **Dismiss**
5. Panel link **Update userscript** opens the raw install URL

## GitHub Actions

- [`deploy.yml`](.github/workflows/deploy.yml) — build/push API image to GHCR + SSH deploy
- [`build-android.yml`](.github/workflows/build-android.yml) — signed APK → GitHub Releases (`android-latest` + `android-v*`)

## License

Private / UNLICENSED.
