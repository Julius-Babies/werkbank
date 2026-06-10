#!/usr/bin/env bash
set -e

export PUBLIC_BASE_DOMAIN=$(jq -r '.domain_suffix' /data/config.json)

function start_server {
  java -jar /app/server.jar --storage-directory=/data --bind-host=127.0.0.1
}

function start_web {
  cd /app/web
  echo "Using base URL: $PUBLIC_BASE_DOMAIN"
  echo "PUBLIC_BASE_DOMAIN=$PUBLIC_BASE_DOMAIN" > .env
  echo "PUBLIC_BASE_URL=https://$PUBLIC_BASE_DOMAIN" >> .env
  bun index.js
}

function start_proxy {
  echo "Starting Caddy reverse proxy..."
  cp /app/deploy/Caddyfile /tmp/Caddyfile
  sed "s/BASE_DOMAIN/${PUBLIC_BASE_DOMAIN}/g" /tmp/Caddyfile > /tmp/Caddyfile.generated
  caddy run --config /tmp/Caddyfile.generated --adapter caddyfile
}

start_server & start_web & start_proxy & wait -n; exit $?