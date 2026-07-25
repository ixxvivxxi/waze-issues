#!/usr/bin/env bash
# Run on the VPS (requires sudo password interactively):
#   ssh myvps-tunnel
#   ~/waze-issues/deploy/install-nginx.sh
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
sudo cp "$DIR/nginx-waze-issues.conf" /etc/nginx/sites-available/waze-issues
sudo ln -sf /etc/nginx/sites-available/waze-issues /etc/nginx/sites-enabled/waze-issues
sudo nginx -t
sudo systemctl reload nginx
if [[ ! -d /etc/letsencrypt/live/waze-issues.ster.by ]]; then
  sudo certbot --nginx -d waze-issues.ster.by --non-interactive --agree-tos --register-unsafely-without-email --redirect \
    || sudo certbot --nginx -d waze-issues.ster.by
fi
echo "NGINX_OK — https://waze-issues.ster.by/"
