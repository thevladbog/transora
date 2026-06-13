package tickets_test

import (
	"encoding/json"
	"path/filepath"
	"testing"

	"github.com/transora/station-agent/internal/protocol"
	"github.com/transora/station-agent/internal/tickets"
)

func TestUpdaterAppliesTicketUsed(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "tickets.db")
	cache, err := tickets.Open(dbPath)
	if err != nil {
		t.Fatalf("open cache: %v", err)
	}
	defer cache.Close()

	if err := cache.UpsertBatch([]tickets.Ticket{{
		TicketID:     "11111111-1111-1111-1111-111111111111",
		TicketNumber: "T1-001",
		TripID:       "22222222-2222-2222-2222-222222222222",
		Status:       "ISSUED",
	}}); err != nil {
		t.Fatalf("seed ticket: %v", err)
	}

	updater := tickets.NewUpdater(cache)
	payload := protocol.TicketUsedPayload{
		TicketID:      "11111111-1111-1111-1111-111111111111",
		TicketNumber:  "T1-001",
		TripID:        "22222222-2222-2222-2222-222222222222",
		Status:        "USED",
		PassengerName: "Alice",
		SeatNumber:    4,
		StationID:     "00000000-0000-0000-0000-000000000001",
		ScannedAt:     "2025-07-15T10:00:00Z",
	}
	raw, err := json.Marshal(payload)
	if err != nil {
		t.Fatalf("marshal payload: %v", err)
	}
	if err := updater.HandleMessage(protocol.MsgTicketUsed, raw); err != nil {
		t.Fatalf("handle ticket.used: %v", err)
	}

	row, err := cache.GetByID(payload.TicketID)
	if err != nil {
		t.Fatalf("get ticket: %v", err)
	}
	if row.Status != "USED" {
		t.Fatalf("expected USED, got %s", row.Status)
	}
	if row.PassengerName != "Alice" {
		t.Fatalf("expected passenger Alice, got %s", row.PassengerName)
	}
}

func TestUpdaterAppliesTicketIssued(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "tickets.db")
	cache, err := tickets.Open(dbPath)
	if err != nil {
		t.Fatalf("open cache: %v", err)
	}
	defer cache.Close()

	updater := tickets.NewUpdater(cache)
	payload := protocol.TicketEventPayload{
		TicketID:      "33333333-3333-3333-3333-333333333333",
		TicketNumber:  "T1-002",
		TripID:        "22222222-2222-2222-2222-222222222222",
		Status:        "ISSUED",
		PassengerName: "Bob",
		SeatNumber:    7,
		StationID:     "00000000-0000-0000-0000-000000000001",
		ScannedAt:     "2025-07-15T09:00:00Z",
	}
	raw, err := json.Marshal(payload)
	if err != nil {
		t.Fatalf("marshal payload: %v", err)
	}
	if err := updater.HandleMessage(protocol.MsgTicketIssued, raw); err != nil {
		t.Fatalf("handle ticket.issued: %v", err)
	}

	row, err := cache.GetByID(payload.TicketID)
	if err != nil {
		t.Fatalf("get ticket: %v", err)
	}
	if row.Status != "ISSUED" {
		t.Fatalf("expected ISSUED, got %s", row.Status)
	}
	if row.PassengerName != "Bob" {
		t.Fatalf("expected passenger Bob, got %s", row.PassengerName)
	}
}

func TestUpdaterAppliesTicketRefunded(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "tickets.db")
	cache, err := tickets.Open(dbPath)
	if err != nil {
		t.Fatalf("open cache: %v", err)
	}
	defer cache.Close()

	if err := cache.UpsertBatch([]tickets.Ticket{{
		TicketID:      "44444444-4444-4444-4444-444444444444",
		TicketNumber:  "T1-003",
		TripID:        "22222222-2222-2222-2222-222222222222",
		Status:        "ISSUED",
		PassengerName: "Carol",
		SeatNumber:    9,
	}}); err != nil {
		t.Fatalf("seed ticket: %v", err)
	}

	updater := tickets.NewUpdater(cache)
	payload := protocol.TicketEventPayload{
		TicketID:      "44444444-4444-4444-4444-444444444444",
		TicketNumber:  "T1-003",
		TripID:        "22222222-2222-2222-2222-222222222222",
		Status:        "REFUNDED",
		PassengerName: "Carol",
		SeatNumber:    9,
		StationID:     "00000000-0000-0000-0000-000000000001",
		ScannedAt:     "2025-07-15T11:00:00Z",
	}
	raw, err := json.Marshal(payload)
	if err != nil {
		t.Fatalf("marshal payload: %v", err)
	}
	if err := updater.HandleMessage(protocol.MsgTicketRefunded, raw); err != nil {
		t.Fatalf("handle ticket.refunded: %v", err)
	}

	row, err := cache.GetByID(payload.TicketID)
	if err != nil {
		t.Fatalf("get ticket: %v", err)
	}
	if row.Status != "REFUNDED" {
		t.Fatalf("expected REFUNDED, got %s", row.Status)
	}
}
