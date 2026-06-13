# Transora Station Agent

Go sidecar for a bus station LAN: persistent WebSocket to core, SQLite schedule cache, offline boarding buffer, HTTP reverse proxy.

## Prerequisites

- Go 1.22+ (local build)
- Running Transora backend (`:8080`)

## Authentication

**Auto-login (recommended for dev/docker):**

```yaml
core:
  login: station_agent
  password: station_agent
```

Or env: `CORE_LOGIN`, `CORE_PASSWORD`, optional `STATION_AGENT_TOKEN` override.

**Manual token:**

```bash
curl -s http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{
    "login": "station_agent",
    "password": "station_agent",
    "stationId": "00000000-0000-0000-0000-000000000001"
  }' | jq -r .accessToken
```

Set as `STATION_AGENT_TOKEN` or `core.auth_token` in config.

## Local run

```bash
cp config.yaml.example config.yaml
go run ./cmd/agent --config config.yaml
```

## Smoke checks

```bash
curl http://localhost:8081/agent/status
curl http://localhost:8081/schedule/board?type=MAIN
curl http://localhost:8081/boarding/buffer/status
curl http://localhost:8081/api/board/departures?stationCode=T1   # proxied to core
```

## SSE mode stream

```javascript
const es = new EventSource('http://localhost:8081/agent/status/stream')
es.onmessage = (event) => {
  const { mode, message } = JSON.parse(event.data)
  console.log(mode, message)
}
```

## Boarding (TSD offline)

Sync manifest while online (before going offline):

```bash
curl -X POST http://localhost:8081/boarding/trips/<trip-id>/sync
curl http://localhost:8081/boarding/trips/<trip-id>/passengers
```

Scan (online validates via core; offline uses local ticket cache):

```bash
curl -X POST http://localhost:8081/boarding/scan \
  -H 'Content-Type: application/json' \
  -d '{
    "ticket_id": "<uuid>",
    "trip_id": "<uuid>",
    "operator_id": "inspector-1"
  }'
```

Offline responses include `scanResult` (`BOARDED`, `ALREADY_USED`, `WRONG_TRIP`, `REFUNDED`, `INVALID_TICKET`), `dataSource: local_cache`, and passenger details when known.

When core is offline, validated scans are buffered in SQLite and flushed via `POST /api/boarding/sync` on reconnect. On reconnect the agent also syncs ticket manifests for active trips (`OPEN`, `BOARDING`, `PLANNED`) when `boarding.sync_trips_on_connect` is true (default).

While connected, core pushes `ticket.used` over WSS when any device scans a ticket online — the agent updates its local manifest immediately so other TSDs see `ALREADY_USED` without waiting for reconnect resync.

Config:

```yaml
boarding:
  ticket_cache_db_path: "./data/ticket_cache.db"
  sync_trips_on_connect: true
```

Env: `TICKET_CACHE_DB`.

## Docker Compose

```bash
docker compose up -d station-agent
curl http://localhost:8081/agent/status
```

No manual token export required — entrypoint waits for backend health and agent auto-logins.

## Modes

| Mode | Behavior |
|------|----------|
| `ONLINE` | WSS connected; proxy forwards to core |
| `DEGRADED` | Reconnecting; boarding tries core with 3s timeout then buffers |
| `OFFLINE` | No core link >30s; proxy returns `503 STATION_OFFLINE` |

Schedule cache (`/schedule/*`) and boarding buffer always served locally.

## Tests

```bash
go test ./...
```
