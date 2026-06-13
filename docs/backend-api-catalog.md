# Transora Backend API Catalog

Modular monolith base path: `/api` (auth under `/auth`).

**Gap analysis** (implemented vs spec): [backend-gap-analysis.md](backend-gap-analysis.md).

## Auth (`/auth`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/auth/login` | Public | Login with credentials and optional `stationId` |
| POST | `/auth/refresh` | Public | Refresh access token |
| POST | `/auth/logout` | Bearer | Revoke session |
| GET | `/auth/me` | Bearer | Current user profile and permissions |
| GET | `/auth/jwks.json` | Public | JWT public keys |

## IAM Admin (`/api/admin/users`, `/api/admin/service-tokens`)

| Method | Path | Permission / Auth | Description |
|--------|------|-------------------|-------------|
| GET | `/api/admin/users` | `users:view` | List users |
| GET | `/api/admin/users/{id}` | `users:view` | User detail with assignments (`assignmentId`) |
| POST | `/api/admin/users` | `users:create` | Create user (`userType`: `USER` \| `SERVICE`; password optional for SERVICE) |
| POST | `/api/admin/users/{id}/assignments` | `users:create` | Assign station role |
| POST | `/api/admin/users/{id}/deactivate` | `users:deactivate` | Deactivate user + revoke refresh tokens (BR-IAM-004) |
| POST | `/api/admin/users/{id}/activate` | `users:deactivate` | Re-enable account |
| POST | `/api/admin/users/{id}/change-password` | `users:edit` | Admin reset password |
| DELETE | `/api/admin/users/{id}/assignments/{assignmentId}` | `users:edit` | Revoke station assignment |
| POST | `/api/admin/service-tokens` | superuser | Create token for SERVICE user; response includes `tokenValue` once |
| GET | `/api/admin/service-tokens` | superuser | List active tokens (no secret) |
| DELETE | `/api/admin/service-tokens/{tokenId}` | superuser | Revoke service token |

Service token auth: `Authorization: Bearer st_<48 hex chars>` (SHA-256 hash stored). Optional `X-Station-ID` for station context. SERVICE accounts cannot password-login (BR-IAM-005).

## Scheduling (`/api/trips`, `/api/board`)

| Method | Path | Permission | Description |
|--------|------|------------|-------------|
| POST | `/api/trips` | `schedule:create` | Create flat trip with seats (legacy) |
| POST | `/api/trips/from-route` | `schedule:create` | Create trip from route template with `trip_stops` |
| GET | `/api/trips` | `schedule:view` | List trips |
| GET | `/api/trips/{id}?include=stops` | `schedule:view` | Get trip (optional stops) |
| PATCH | `/api/trips/{id}` | `schedule:edit` | Update status/platform/delay/vehicle/driver |
| GET | `/api/trips/{id}/stops` | `schedule:view` | List trip stops |
| PATCH | `/api/trips/{id}/stops/{stopId}` | `schedule:edit` | Update stop times/status |
| POST | `/api/trips/{id}/stops/{stopId}/arrive` | `schedule:edit` | Record stop arrival |
| POST | `/api/trips/{id}/stops/{stopId}/depart` | `schedule:edit` | Record stop departure |
| POST | `/api/schedules/generate` | `schedule:create` | Generate trips for horizon |
| POST | `/api/stations/{code}/schedules/generate` | `schedule:create` | Generate trips for station routes |
| GET | `/api/board/departures` | Public | Departure board (stop-aware via `trip_stops`; query `stationCode`, optional `windowBeforeMin` / `windowAfterMin`) |
| GET | `/api/board/arrivals` | Public | Arrival board (stop-aware; includes `IN_TRANSIT` / transit stops) |
| GET | `/api/board/platform/{platformNumber}` | Public | Platform board subset of departures |
| GET | `/api/stations/{code}/trips` | `schedule:view` | Station-scoped trip list for board window (same projection as boards) |
| WS | `/ws/board/{boardId}` | Public | Full board snapshot; envelope `{ type: "BOARD_STATE_FULL", payload: {...} }` |

Board row fields (departures/arrivals): `tripId`, `time`, `displayTime`, `route`, `tripNumber`, `platform`, `displayStatus`, `delayMinutes`, `direction` (`DEPARTURE` \| `ARRIVAL` \| `TRANSIT`), `stopOrder`. Legacy flat trips (`POST /api/trips` without stops) still appear on departures at `departureStationCode`.

Display time per stop: `actual_*` → `estimated_*` → `scheduled_*` (arrival fields on arrivals board, departure fields on departures).

Outbox events use `scheduling.trip.*` subjects (e.g. `scheduling.trip.created`, `scheduling.trip.status_changed`).

## Station agent (core WebSocket)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| WS | `/ws/stations` | Bearer + `X-Station-ID` | Station-agent link; role `STATION_AGENT` required |

**Agent → core messages:** `sync.request`, `pong`, `agent.status`

**Core → agent messages:** `sync.snapshot`, `trip.created`, `trip.updated`, `trip.cancelled`, `trip.delay_updated`, `ticket.used`, `ping`, `sync.force`

`ticket.used` payload: `{ ticketId, ticketNumber, tripId, status, passengerName, seatNumber, stationId, scannedAt }` — updates local ticket manifest when another device scans online.

`sync.snapshot` payload: `{ stationId, generatedAt, version, trips[] }` where each trip includes `tripId`, `tripNumber`, `status`, `delayMinutes`, `displayTime`, `directionStop`, `platformNumber`, `stops[]`.

Dev user: `station_agent` / `station_agent` with `stationId` = T1 (`00000000-0000-0000-0000-000000000001`).

**Local station-agent HTTP** (Go sidecar, default `:8081`):

| Method | Path | Description |
|--------|------|-------------|
| GET | `/agent/status` | Agent status snapshot |
| GET | `/agent/mode` | Current mode + since |
| GET | `/agent/status/stream` | SSE stream of mode changes (`{ mode, message, since }`) |
| GET | `/schedule/board?type=MAIN` | Departure board from SQLite cache |
| GET | `/schedule/trips` | Cached trips list |
| POST | `/boarding/scan` | Online → proxy `POST /api/boarding/scan`; offline/degraded → local cache validation + buffer |
| GET | `/boarding/buffer/status` | `{ pendingCount, lastSyncAt? }` |
| GET | `/boarding/trips/{tripId}/passengers` | Cached ticket manifest for TSD (`source: cache`) |
| POST | `/boarding/trips/{tripId}/sync` | Pull manifest from core when online |
| ALL | other paths | Reverse proxy to core |

Auto-login: set `CORE_LOGIN` / `CORE_PASSWORD` (or `core.login` / `core.password` in config). Optional override: `STATION_AGENT_TOKEN`.

Offline (`mode=OFFLINE`): proxied routes return `503 STATION_OFFLINE`; cache and boarding buffer routes always available.

## Inventory (`/api/reservations`, `/api/trips/{id}/seats`)

| Method | Path | Permission | Description |
|--------|------|------------|-------------|
| POST | `/api/reservations` | `tickets:sell` | Reserve seat (`fromStopOrder`/`toStopOrder`; segment overlap check **BR-INV-050–052** on multi-stop trips; transit/quota/block rules apply; `409 SEGMENT_OCCUPIED`, `NOT_IN_QUOTA`, `MANUAL_BLOCK`, `TRANSIT_CLOSED`) |
| POST | `/api/reservations/{id}/cancel` | `tickets:sell` | Cancel reservation |
| GET | `/api/trips/{tripId}/seats` | `inventory:view` | Flat seat list; fields `seatNumber`, `status`, `requiresReaccommodation` (true when sold seat exceeds new vehicle capacity after swap) |
| GET | `/api/trips/{tripId}/seats?toStopOrder=` | `inventory:view` | Station-scoped seat map with `availableForStation`, `restrictionReason` (includes `REACCOMMODATION_REQUIRED`), optional `transitGate` (**BR-INV-051**, spec §9.2) |

## Sales (`/api/shifts`, `/api/orders`, `/api/tickets`)

| Method | Path | Permission | Description |
|--------|------|------------|-------------|
| POST | `/api/shifts` | `shifts:manage` | Open cashier shift |
| GET | `/api/shifts/open` | `shifts:manage` | List open shifts |
| POST | `/api/shifts` | `shifts:manage` | Open shift (async `shift.opened` → fiscal KKT open, `fiscal_shift_no` in V21) |
| POST | `/api/shifts/{id}/close` | `shifts:manage` | Close shift (async Z-report via outbox `shift.closed` → `sales.fiscal_receipts`) |
| POST | `/api/orders` | `tickets:sell` | One-shot sale: reserve + payment + fiscal + issue (1–10 tickets) |
| POST | `/api/tickets` | `tickets:sell` | Issue from existing reservation via unified saga (`docType`, `docNumber`, `paymentType` required; returns `orderId`) |
| GET | `/api/tickets/{id}` | `tickets:view` | Get ticket |
| GET | `/api/tickets?tripId=` | `tickets:view` | List tickets by trip |
| POST | `/api/tickets/{id}/refund` | `tickets:refund` | Refund issued ticket |
| GET | `/api/tickets/{id}/document` | `documents:print` | Ticket PDF (80mm thermal); logs each download to `documents.print_log` (`TICKET_PRINT` / `TICKET_REPRINT`); optional query `stationCode`, `posId` |

**Internal (no public API yet):** fiscal receipts (`sales.fiscal_receipts`) — SALE on order/ticket issue, REFUND on ticket refund, Z_REPORT on shift close; linked via `orders.fiscal_receipt_id`, `refunds.fiscal_receipt_id`, `shifts.z_report_fiscal_receipt_id`. Outbox side effects: `shift.opened` opens fiscal shift and stores `shifts.fiscal_shift_no`; reservation/seat events write `admin.audit_log` and refresh boards; `scheduling.schedule.updated` triggers trip regeneration for the route.

## Dispatcher (Phase I)

Station scope enforced from JWT `station_id`.

| Method | Path | Permission | Description |
|--------|------|------------|-------------|
| POST | `/api/sales-restrictions` | `inventory:toggle_restriction` | Create station sales quota (`tripId` or `scheduleEntryId`) |
| POST | `/api/sales-restrictions/{id}/pause` | `inventory:toggle_restriction` | Pause active sales restriction |
| POST | `/api/sales-restrictions/{id}/resume` | `inventory:toggle_restriction` | Resume paused sales restriction |
| POST | `/api/seat-blocks` | `inventory:manual_block` | Block seat on trip |
| POST | `/api/seat-blocks/{id}/release` | `inventory:manual_block` | Release manual seat block (creator or superuser) |
| GET | `/api/transit-gates?tripId=` | `inventory:open_transit_gate` | List transit gates for trip (station-scoped; all gates for superuser) |
| POST | `/api/transit-gates/{gateId}/open` | `inventory:open_transit_gate` | Open transit sales after bus arrival (requires stop ARRIVED, seats AVAILABLE) |
| POST | `/api/transit-gates/{gateId}/close` | `inventory:close_transit_gate` | Close transit sales after boarding completed |
| GET | `/api/announcements/queue` | `announcements:manage_queue` | List queue (`queuePaused` flag included) |
| GET | `/api/announcements/templates` | `announcements:manage_queue` | List active announcement templates |
| POST | `/api/announcements/queue/pause` | `announcements:manage_queue` | Pause playback for current station |
| POST | `/api/announcements/queue/resume` | `announcements:manage_queue` | Resume playback for current station |
| GET | `/api/announcements/{id}` | `announcements:manage_queue` | Get announcement |
| GET | `/api/announcements/{id}/audio` | `announcements:play_audio` | Download synthesized WAV (station-agent) |
| POST | `/api/announcements` | `announcements:manage_queue` | Create announcement (queued → TTS → WS `audio.play`) |
| PUT | `/api/announcements/{id}` | `announcements:manage_queue` | Update announcement |
| DELETE | `/api/announcements/{id}` | `announcements:manage_queue` | Delete announcement |
| POST | `/api/display-boards/register` | `announcements:manage_queue` | Register display board agent |
| POST | `/api/display-boards/{id}/heartbeat` | `announcements:manage_queue` | Display board heartbeat |

## Boarding (Phase J)

| Method | Path | Permission | Description |
|--------|------|------------|-------------|
| POST | `/api/boarding/scan` | `boarding:scan` | Scan ticket → `USED` (idempotent) |
| POST | `/api/boarding/sync` | `boarding:scan` | Batch offline sync |
| GET | `/api/boarding/trips/{tripId}/tickets` | `boarding:scan` | Ticket manifest for station-agent offline cache |
| GET | `/api/trips/{id}/boarding/stats` | `boarding:view_stats` | Boarding statistics |

## Admin Reporting & Config (Phase K)

| Method | Path | Permission | Description |
|--------|------|------------|-------------|
| GET | `/api/admin/reports/station-revenue` | `reports:view_station` | Station revenue report |
| GET | `/api/admin/reports/passenger-flow` | `reports:view_station` | Passenger flow report |
| GET | `/api/admin/audit` | `users:view` | Admin + auth audit logs |
| GET | `/api/admin/tariffs` | `settings:manage_tariffs` | List tariffs |
| POST | `/api/admin/tariffs` | `settings:manage_tariffs` | Create tariff |
| PUT | `/api/admin/tariffs/{id}` | `settings:manage_tariffs` | Update tariff |
| DELETE | `/api/admin/tariffs/{id}` | `settings:manage_tariffs` | Delete tariff |
| GET | `/api/admin/refund-policies` | `settings:manage_tariffs` | List refund policies |
| POST | `/api/admin/refund-policies` | `settings:manage_tariffs` | Create refund policy |
| PUT | `/api/admin/refund-policies/{id}` | `settings:manage_tariffs` | Update refund policy |
| DELETE | `/api/admin/refund-policies/{id}` | `settings:manage_tariffs` | Delete refund policy |

## System

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/actuator/health` | Public | Health check |
| GET | `/docs` | Public | Scalar API docs |
| GET | `/v3/api-docs` | Public | OpenAPI JSON |

## E2E Script (Phase L)

```bash
./scripts/e2e-backend.sh
```

Flow: login → create trip → sell → refund → board scan → download document.
