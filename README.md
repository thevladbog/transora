# Transora

Transora is a bus station management system. The repository starts as a Kotlin-first modular monolith with explicit domain modules and room to split physical agents or services later.

## Current Stack

- Java 25 LTS
- Kotlin 2.4.0
- Spring Boot 4.1.0
- Gradle 9.5.1
- PostgreSQL 18.4
- Flyway

## Modules

- `backend:app` - Spring Boot application entry point.
- `backend:shared` - shared domain primitives.
- `backend:iam` - identity and access management.
- `backend:scheduling` - stations, routes, schedules and trips.
- `backend:inventory` - seat maps, availability and reservations.
- `backend:sales` - shifts, orders, tickets, payments and refunds.
- `backend:documents` - printable and downloadable documents.
- `backend:notifications` - display and announcement events.

## Local Development

Start PostgreSQL:

```bash
docker compose up -d postgres
```

Run the backend:

```bash
./gradlew :backend:app:bootRun --args='--spring.profiles.active=dev'
```

Or start PostgreSQL and backend together:

```bash
docker compose up -d
```

API documentation (Scalar): http://localhost:8080/docs

OpenAPI spec: http://localhost:8080/v3/api-docs

Health endpoint:

```bash
curl http://localhost:8080/actuator/health
```

## First Vertical Slice

Create a trip with 45 seats:

```bash
curl -X POST http://localhost:8080/api/trips \
  -H "Content-Type: application/json" \
  -d '{
    "routeNumber": "101",
    "departureStation": "Transora Central",
    "arrivalStation": "North Terminal",
    "departureTime": "2026-06-14T10:00:00Z",
    "platform": "3",
    "seatCount": 45
  }'
```

Reserve a seat:

```bash
curl -X POST http://localhost:8080/api/reservations \
  -H "Content-Type: application/json" \
  -d '{
    "tripId": "replace-with-trip-id",
    "seatNumber": 12
  }'
```

List seats for a trip:

```bash
curl http://localhost:8080/api/trips/replace-with-trip-id/seats
```

Open a cashier shift:

```bash
curl -X POST http://localhost:8080/api/shifts \
  -H "Content-Type: application/json" \
  -d '{
    "stationName": "Transora Central",
    "cashierName": "cashier-1"
  }'
```

Issue a ticket from an active reservation:

```bash
curl -X POST http://localhost:8080/api/tickets \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -d '{
    "reservationId": "replace-with-reservation-id",
    "shiftId": "replace-with-shift-id",
    "passengerName": "Ivan Petrov",
    "docType": "PASSPORT_RF",
    "docNumber": "4510 123456",
    "paymentType": "CASH"
  }'
```

Close a cashier shift:

```bash
curl -X POST http://localhost:8080/api/shifts/replace-with-shift-id/close
```

List departure board:

```bash
curl "http://localhost:8080/api/board/departures?stationCode=T1"
```

Download ticket PDF:

```bash
curl http://localhost:8080/api/tickets/replace-with-ticket-id/document -o ticket.pdf
```
