
## Frontend: super-admin + station admin (multi-host)

Two static deploy artifacts share one codebase (`frontend/apps/admin`) with `VITE_APP_TIER`:

| Artifact | Build command | Purpose |
|----------|---------------|---------|
| Super-admin (network) | `VITE_API_URL=… VITE_APP_TIER=network pnpm --filter @transora/super-admin build` | Branches, catalog, network users |
| Station admin | `VITE_API_URL=… VITE_APP_TIER=station pnpm --filter @transora/admin build:station` | Ops + branch switcher |

Each host only needs `VITE_API_URL` pointing at the shared backend. Add each admin origin to backend CORS allowlist.

**Station-agent** on site LAN: set `core.registration_code` (from super-admin) once; credentials persist in `provisioned.yaml`.

### Docker Compose `station-site` profile

Provision a new site agent against a running stack:

```bash
# 1. Create branch + code in super-admin, then:
REGISTRATION_CODE=TR-XXXX-XXXX docker compose --profile station-site up -d station-agent
```

Dev stack (password login, fixed `STATION_ID`) uses the default profile:

```bash
docker compose up -d station-agent
```

See [frontend-multi-host.md](frontend-multi-host.md) for admin/super-admin static deploys.
