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

Default API base in the APK is `https://waze-issues.ster.by` (see `android/app/build.gradle.kts` → `DEFAULT_API_BASE`).

In the app: **Settings** → nick + API key (same as server `API_KEY` in `deploy/.env.prod`). Use split-screen with Waze.

### Build APK (Docker — recommended on this machine)

There is usually **no** local Android Studio / `ANDROID_HOME`. Build with JDK Docker image + a cached SDK directory.

**One-liner script** (from repo root `/home/anton/projects/waze-issues`):

```bash
chmod +x android/build-apk.sh
./android/build-apk.sh            # writes android/app/build/outputs/apk/debug/app-debug.apk
                                  # and copies to deploy/public/app.apk
./android/build-apk.sh --publish  # also scp to VPS (SSH host myvps-tunnel / ster@95.128.71.94)
```

**Manual equivalent:**

```bash
cd /home/anton/projects/waze-issues
mkdir -p "$HOME/.android-sdk-docker"

docker run --rm \
  -v "$PWD/android:/project" \
  -v "$HOME/.android-sdk-docker:/opt/android-sdk" \
  -w /project \
  eclipse-temurin:17-jdk \
  bash -lc '
    set -e
    export ANDROID_HOME=/opt/android-sdk
    export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
    if [ ! -d "$ANDROID_HOME/cmdline-tools/latest" ]; then
      apt-get update -qq && apt-get install -y -qq wget unzip >/dev/null
      mkdir -p "$ANDROID_HOME/cmdline-tools"
      cd /tmp
      wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdtools.zip
      unzip -q cmdtools.zip -d "$ANDROID_HOME/cmdline-tools"
      mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    fi
    yes | sdkmanager --licenses >/dev/null || true
    sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
    cd /project && ./gradlew assembleDebug --no-daemon
  '

# Output:
#   android/app/build/outputs/apk/debug/app-debug.apk

cp android/app/build/outputs/apk/debug/app-debug.apk deploy/public/app.apk
scp deploy/public/app.apk myvps-tunnel:~/waze-issues/deploy/public/app.apk
# Download: https://waze-issues.ster.by/app.apk
```

Notes for another agent/chat:

| Item | Value |
|------|-------|
| Repo | `/home/anton/projects/waze-issues` (GitHub `ixxvivxxi/waze-issues`) |
| SDK cache | `~/.android-sdk-docker` (reuse across builds; first run is slow) |
| Docker image | `eclipse-temurin:17-jdk` |
| Gradle | `android/gradlew assembleDebug` |
| Publish SSH | host `myvps-tunnel` → user `ster`, key `~/.ssh/id_ed25519_autossh` |
| Live APK URL | `https://waze-issues.ster.by/app.apk` |
| CI | `.github/workflows/build-android.yml` builds APK artifact only (no auto-publish yet) |

If you have a local SDK instead: `cd android && ./gradlew assembleDebug`.

## WME userscript

Install [`wme-waze-issues.user.js`](wme-waze-issues.user.js) in Tampermonkey (also mirrored in [wme-scripts](https://github.com/ixxvivxxi/wme-scripts)).

1. Open WME → sidebar tab **Drive reports**
2. Paste API key, leave API base `https://waze-issues.ster.by`
3. Enable **Show pending reports** (zoom 14+)
4. Speed limits draw as circular road signs (red ring + number); bumps as yellow diamonds
5. Click a marker → **Done** / **Dismiss**

## GitHub Actions

- [`build-server.yml`](.github/workflows/build-server.yml) — `docker build` only
- [`build-android.yml`](.github/workflows/build-android.yml) — assemble debug APK artifact

**Deploy workflow is deferred** until SSH/registry secrets can be added.

## License

Private / UNLICENSED.
