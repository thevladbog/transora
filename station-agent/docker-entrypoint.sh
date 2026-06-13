#!/bin/sh
set -eu

CORE_HTTP_URL="${CORE_HTTP_URL:-http://backend:8080}"
CORE_LOGIN="${CORE_LOGIN:-station_agent}"
CORE_PASSWORD="${CORE_PASSWORD:-station_agent}"
STATION_ID="${STATION_ID:-00000000-0000-0000-0000-000000000001}"

echo "Waiting for core at ${CORE_HTTP_URL}..."
until curl -sf "${CORE_HTTP_URL}/actuator/health" >/dev/null; do
  sleep 2
done

if [ -z "${STATION_AGENT_TOKEN:-}" ]; then
  echo "Obtaining station agent token..."
  STATION_AGENT_TOKEN=$(curl -sf "${CORE_HTTP_URL}/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"login\":\"${CORE_LOGIN}\",\"password\":\"${CORE_PASSWORD}\",\"stationId\":\"${STATION_ID}\"}" \
    | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
  export STATION_AGENT_TOKEN
fi

exec /app/station-agent --config /app/config.yaml
