#!/usr/bin/env sh
# Builds the compose images one at a time. Docker Compose/BuildKit fails to build several images in one go when the
# folder path has non-ASCII characters (e.g. an em dash): "x-docker-expose-session-sharedkey ... non-printable ASCII".
# Building them one by one avoids it. Then start with: docker compose up -d
set -e
cd "$(dirname "$0")/.."
for s in user-service wallet-service transaction-service notification-service api-gateway wallet-ui; do
  docker compose build "$s"
done
