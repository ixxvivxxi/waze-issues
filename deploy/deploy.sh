#!/usr/bin/env bash
# Pull the GHCR API image and recreate the stack.
# Run on the VPS from ~/waze-issues/deploy (also called by GitHub Actions).
set -euo pipefail
cd "$(dirname "$0")"

if [[ ! -f .env.prod ]]; then
  echo "Missing .env.prod — copy from .env.prod.example and fill secrets" >&2
  exit 1
fi

dc() { docker compose -f docker-compose.prod.yml --env-file .env.prod "$@"; }

echo "==> Pull API image"
dc pull api

echo "==> Recreate stack"
dc up -d --remove-orphans

echo "==> Status"
dc ps
echo "DEPLOY_OK"
