#!/usr/bin/env bash
set -e

export PUBLIC_BASE_URL=$(jq '.domain_suffix' /data/config.json -r)

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
  sed -i "s/BASE_DOMAIN/${$PUBLIC_BASE_DOMAIN}/g" /app/deploy/Caddyfile
  caddy run --config /app/deploy/Caddyfile --adapter caddyfile
}

start_server & start_web & start_proxy & wait