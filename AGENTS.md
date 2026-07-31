# Agent guide — waze-issues

Private tool for flagging Waze map problems while driving, then reviewing them in WME.

Owners / agents: read this before changing code. Human-oriented overview stays in [`README.md`](README.md).

## What it is

```
Android (tap / bubble)  →  Nest API  →  Postgres (map_reports)
                                      ↑
WME userscript (editors) ─────────────┘
```

Trusted users only — **no API key**. Nick is a free-text `reporterNick` from the phone.

**Production:** `https://waze-issues.ster.by`  
**Repo:** `ixxvivxxi/waze-issues`  
**Image:** `ghcr.io/ixxvivxxi/waze-issues:api`  
**VPS SSH:** host alias `myvps` (user `ster`)

## Layout

| Path | Role |
|------|------|
| `server/` | NestJS 11 + TypeORM + Postgres |
| `android/` | Kotlin Compose reporter (full UI + floating bubble) |
| `deploy/` | Compose + nginx snippets for VPS |
| `wme-waze-issues.user.js` | Tampermonkey script for WME |
| `.github/workflows/` | `deploy.yml` (API), `build-android.yml` (APK) |

## Domain model

Table `map_reports` (`MapReportEntity`):

- `issueType`: `speed_bump_add` | `speed_bump_remove` | `speed_limit` | `general`
- `lon` / `lat` doubles (no PostGIS)
- `trajectory` JSONB + `headingDeg` (from post-tap GPS trail)
- `reporterNick`, `description`, `status` (`pending` \| `done` \| `dismissed`)
- `payload` JSONB — for speed: `valueKmh`, optional `lengthM` (0…1000 step 50; `0` = until signs)
- `clientEventId` — idempotent create from Android local UUID

BBox query side limit: **≤ 0.35°** (`ReportsService.bbox`).

## Android architecture (important)

Process-wide reporter lives in `WazeIssuesApp.reports` → [`ReportController`](android/app/src/main/java/by/ster/wazeissues/ui/ReportController.kt).

- [`MainViewModel`](android/app/src/main/java/by/ster/wazeissues/ui/MainViewModel.kt) — thin Activity wrapper (acquire/release + delegates)
- [`AppRoot`](android/app/src/main/java/by/ster/wazeissues/ui/AppRoot.kt) — full-screen Compose UI
- [`LiveLocation`](android/app/src/main/java/by/ster/wazeissues/location/LiveLocation.kt) — continuous GPS; snapshots at tap time
- [`LocationTrailService`](android/app/src/main/java/by/ster/wazeissues/location/LocationTrailService.kt) — ~15s trail after upload for heading
- Bubble package `by.ster.wazeissues.bubble`:
  - `BubbleLauncher` — overlay permission + start/stop
  - `BubbleOverlayService` — `TYPE_APPLICATION_OVERLAY` + ComposeView FGS (`location`)
  - `BubbleOverlayContent` — collapsed hub → arc menu → speed list (same tap / long-press length as main UI)

### Bubble UX contract

1. Tap hub → **freeze GPS** (`captureFrozenFix`) → arc: bump+, general, speed, bump−
2. Speed → grid; tap sends; long-press → length gesture then collapse
3. Back from speed → arc; hub / outside → collapse (clears freeze)
4. Main UI **Bubble** → start overlay + `moveTaskToBack`; overlay **App** → `MainActivity` + stop service
5. Needs `SYSTEM_ALERT_WINDOW`. On Android 13+ **sideloaded** APKs the OS shows “App was denied access” until App info → ⋮ → **Allow restricted settings**, then enable overlay.

Shared report logic / recent list / uploads must stay in `ReportController`, not duplicated.

### Locales

EN / RU / BE via `AppLocales` + `res/values(-ru|-be)/strings.xml`. Add strings to **all three**.

### Versioning

Bump both in `android/app/build.gradle.kts` when publishing:

- `versionCode`
- `versionName`
- `DEFAULT_APK_URL` filename must match `waze-issues-<versionName>.apk`

Build: `./android/build-apk.sh` (Docker + Temurin 17). `--publish` → GitHub Releases `android-latest` + `android-v*`.

**Build gotchas for agents:**

- Prefer Docker (`build-apk.sh`) — host may only have JRE, not JDK.
- Docker builds can leave `android/.gradle` and `android/app/build` **root-owned**; fix with  
  `docker run --rm -v "$PWD/android:/android" alpine chown -R $(id -u):$(id -g) /android/.gradle /android/app/build`
- Do not commit `local.properties`, keystores, or `signing/keystore.properties`.

## Server

- Nest module `reports/` — controller / service / DTO / entity
- Migrations: TypeORM under `server/src/migrations/` — run `npm run migration:run`
- Landing HTML: `server/src/home.page.ts` at `GET /`
- Env: `DATABASE_URL`, `PORT` (see `server/.env.example`)

API surface is documented in README; keep README and this file in sync when adding endpoints.

## Userscript

`wme-waze-issues.user.js` — loads pending reports by bbox, draws markers, Done/Dismiss.  
`@updateURL` / `@downloadURL` point at raw GitHub `main`.

## Deploy

- Push to `main` touching `server/` or `deploy/` → GHCR build + SSH `~/waze-issues/deploy/deploy.sh`
- Compose joins external Docker network `postgres_app-network` (shared `main-postgres`)
- Never commit `deploy/.env.prod`

### Inspecting production reports (ops)

API bbox is capped; for aggregates use SSH + `psql` on VPS (`main-postgres` container), not a world bbox. Do **not** paste DB passwords into chat logs.

## Agent conventions

- **Do not** `git commit` / `git push` unless the user explicitly asks.
- Match existing style: Compose Material3, Nest + class-validator DTOs, no new frameworks without need.
- Prefer extending `ReportController` / shared widgets over copying report upload logic.
- Overlay Compose: avoid naming a method `setPhase` next to `var phase by mutableStateOf` (JVM setter clash) — use `applyPhase`.
- Keep API open (no API key) unless the user requests auth.
- Private / UNLICENSED — do not publish as open source or add a public license without asking.

## Quick commands

```bash
# API local
cd server && cp -n .env.example .env && npm i && npm run migration:run && npm run start:dev

# Android release APK
./android/build-apk.sh

# Health
curl -sS https://waze-issues.ster.by/health
```
