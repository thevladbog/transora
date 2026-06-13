#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
STATION_ID="${STATION_ID:-11111111-1111-1111-1111-111111111001}"

json_field() {
  local json="$1"
  local field="$2"
  echo "$json" | python3 -c "import json,sys; print(json.load(sys.stdin)['$field'])"
}

login() {
  local user="$1"
  local pass="$2"
  curl -sf "$BASE_URL/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"login\":\"$user\",\"password\":\"$pass\",\"stationId\":\"$STATION_ID\"}"
}

echo "==> Login as admin"
ADMIN_TOKEN=$(json_field "$(login admin admin)" accessToken)

echo "==> Create trip"
DEPARTURE=$(python3 -c "from datetime import datetime, timedelta, timezone; print((datetime.now(timezone.utc)+timedelta(hours=3)).strftime('%Y-%m-%dT%H:%M:%SZ'))")
TRIP_JSON=$(curl -sf "$BASE_URL/api/trips" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"routeNumber\":\"101\",\"departureStation\":\"Transora Central\",\"arrivalStation\":\"North Terminal\",\"departureStationCode\":\"T1\",\"departureTime\":\"$DEPARTURE\",\"platform\":\"3\",\"seatCount\":45}")
TRIP_ID=$(json_field "$TRIP_JSON" id)
echo "Trip: $TRIP_ID"

echo "==> Open shift"
SHIFT_JSON=$(curl -sf "$BASE_URL/api/shifts" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"stationName":"Transora Central","cashierName":"e2e-cashier","posId":"POS-E2E-1"}')
SHIFT_ID=$(json_field "$SHIFT_JSON" id)

echo "==> Create order (saga with mock fiscal)"
ORDER_JSON=$(curl -sf "$BASE_URL/api/orders" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{
    \"shiftId\":\"$SHIFT_ID\",
    \"tripId\":\"$TRIP_ID\",
    \"seatNumber\":12,
    \"passengerName\":\"E2E Passenger\",
    \"docType\":\"PASSPORT_RF\",
    \"docNumber\":\"4510 123456\",
    \"paymentType\":\"CASH\"
  }")
TICKET_ID=$(json_field "$ORDER_JSON" ticketId)
TICKET_NUMBER=$(json_field "$ORDER_JSON" ticketNumber)
echo "Order ticket: $TICKET_ID ($TICKET_NUMBER)"

echo "==> Reserve and sell second ticket for refund"
RES2_JSON=$(curl -sf "$BASE_URL/api/reservations" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"tripId\":\"$TRIP_ID\",\"seatNumber\":13}")
RES2_ID=$(json_field "$RES2_JSON" id)
TICKET2_JSON=$(curl -sf "$BASE_URL/api/tickets" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"reservationId\":\"$RES2_ID\",\"shiftId\":\"$SHIFT_ID\",\"passengerName\":\"Refund Passenger\",\"docType\":\"PASSPORT_RF\",\"docNumber\":\"4510 123456\",\"paymentType\":\"CASH\"}")
TICKET2_ID=$(json_field "$TICKET2_JSON" id)

echo "==> Refund second ticket"
curl -sf -X POST "$BASE_URL/api/tickets/$TICKET2_ID/refund" \
  -H "Authorization: Bearer $ADMIN_TOKEN" > /dev/null

echo "==> Trip stops (when route-based trip exists)"
curl -sf "$BASE_URL/api/trips/$TRIP_ID/stops" \
  -H "Authorization: Bearer $ADMIN_TOKEN" > /dev/null || true

echo "==> Transit gates (empty for flat trips without intermediate stops)"
curl -sf "$BASE_URL/api/transit-gates?tripId=$TRIP_ID" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | python3 -c "import json,sys; json.load(sys.stdin)"

echo "==> Board departures (public)"
curl -sf "$BASE_URL/api/board/departures?stationCode=T1" | python3 -c "import json,sys; d=json.load(sys.stdin); assert len(d.get('departures',[]))>=1"

echo "==> Board arrivals (public)"
curl -sf "$BASE_URL/api/board/arrivals?stationCode=T1" | python3 -c "import json,sys; json.load(sys.stdin)"

echo "==> Login as inspector and board scan"
INSPECTOR_TOKEN=$(json_field "$(login inspector inspector)" accessToken)
SCAN_JSON=$(curl -sf "$BASE_URL/api/boarding/scan" \
  -H "Authorization: Bearer $INSPECTOR_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"ticketId\":\"$TICKET_ID\",\"tripId\":\"$TRIP_ID\"}")
echo "Scan: $SCAN_JSON"

echo "==> Boarding stats"
curl -sf "$BASE_URL/api/trips/$TRIP_ID/boarding/stats" \
  -H "Authorization: Bearer $INSPECTOR_TOKEN" > /dev/null

echo "==> Download ticket document"
curl -sf "$BASE_URL/api/tickets/$TICKET_ID/document" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -o /tmp/transora-ticket.pdf
test -s /tmp/transora-ticket.pdf

echo "==> JWKS (public)"
curl -sf "$BASE_URL/auth/jwks.json" | python3 -c "import json,sys; assert 'keys' in json.load(sys.stdin)"

STATION_AGENT_URL="${STATION_AGENT_URL:-}"
if [ -n "$STATION_AGENT_URL" ]; then
  echo "==> Station agent status"
  curl -sf "$STATION_AGENT_URL/agent/status" | python3 -c "import json,sys; print(json.load(sys.stdin)['mode'])"
  curl -sf "$STATION_AGENT_URL/boarding/buffer/status" > /dev/null
  curl -sf "$STATION_AGENT_URL/boarding/trips/00000000-0000-0000-0000-000000000000/passengers" > /dev/null
fi

echo "==> E2E backend flow completed successfully"
