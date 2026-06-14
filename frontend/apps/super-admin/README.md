# Super-admin (network tier)

Network administration UI for Transora: branches, catalog, users, reports.

This package is a **deploy alias** for `@transora/admin` built with `VITE_APP_TIER=network`.

## Deploy

Set the backend URL at build time:

```bash
VITE_API_URL=https://api.transora.example.com pnpm --filter @transora/super-admin build
```

Serve the `frontend/apps/admin/dist` artifact as static files (nginx, CDN, etc.).

## Local dev

```bash
pnpm --filter @transora/super-admin dev
```

Runs on http://localhost:5173 with API proxy to `localhost:8080`.

## Station admin

For station-tier ops UI with branch switcher, use `@transora/admin` with `VITE_APP_TIER=station`:

```bash
VITE_API_URL=https://api.transora.example.com pnpm --filter @transora/admin build:station
```

See [admin README](../admin/README.md) if present, or root deployment docs.
