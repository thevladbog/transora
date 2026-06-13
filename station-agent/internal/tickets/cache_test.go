package tickets_test

import (
	"path/filepath"
	"testing"

	"github.com/transora/station-agent/internal/tickets"
)

func TestTicketCacheUpsertAndLookup(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "tickets.db")
	cache, err := tickets.Open(dbPath)
	if err != nil {
		t.Fatalf("open cache: %v", err)
	}
	defer cache.Close()

	ticket := tickets.Ticket{
		TicketID:      "11111111-1111-1111-1111-111111111111",
		TicketNumber:  "T1-001",
		TripID:        "22222222-2222-2222-2222-222222222222",
		Status:        "ISSUED",
		PassengerName: "Alice",
		SeatNumber:    12,
	}
	if err := cache.UpsertBatch([]tickets.Ticket{ticket}); err != nil {
		t.Fatalf("upsert: %v", err)
	}

	byID, err := cache.GetByID(ticket.TicketID)
	if err != nil {
		t.Fatalf("get by id: %v", err)
	}
	if byID.PassengerName != "Alice" {
		t.Fatalf("unexpected passenger: %s", byID.PassengerName)
	}

	byNumber, err := cache.GetByNumber("T1-001")
	if err != nil {
		t.Fatalf("get by number: %v", err)
	}
	if byNumber.TicketID != ticket.TicketID {
		t.Fatalf("unexpected ticket id: %s", byNumber.TicketID)
	}

	if err := cache.MarkUsed(ticket.TicketID); err != nil {
		t.Fatalf("mark used: %v", err)
	}
	updated, err := cache.GetByID(ticket.TicketID)
	if err != nil {
		t.Fatalf("get updated: %v", err)
	}
	if updated.Status != "USED" {
		t.Fatalf("expected USED, got %s", updated.Status)
	}
}

func TestTicketCacheReplaceTrip(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "tickets.db")
	cache, err := tickets.Open(dbPath)
	if err != nil {
		t.Fatalf("open cache: %v", err)
	}
	defer cache.Close()

	tripID := "22222222-2222-2222-2222-222222222222"
	if err := cache.ReplaceTrip(tripID, []tickets.Ticket{
		{
			TicketID:     "11111111-1111-1111-1111-111111111111",
			TicketNumber: "T1-001",
			TripID:       tripID,
			Status:       "ISSUED",
		},
		{
			TicketID:     "33333333-3333-3333-3333-333333333333",
			TicketNumber: "T1-002",
			TripID:       tripID,
			Status:       "ISSUED",
		},
	}); err != nil {
		t.Fatalf("replace trip: %v", err)
	}

	count, err := cache.CountByTrip(tripID)
	if err != nil {
		t.Fatalf("count: %v", err)
	}
	if count != 2 {
		t.Fatalf("expected 2 tickets, got %d", count)
	}

	if err := cache.ReplaceTrip(tripID, []tickets.Ticket{
		{
			TicketID:     "44444444-4444-4444-4444-444444444444",
			TicketNumber: "T1-003",
			TripID:       tripID,
			Status:       "ISSUED",
		},
	}); err != nil {
		t.Fatalf("replace trip again: %v", err)
	}
	count, err = cache.CountByTrip(tripID)
	if err != nil {
		t.Fatalf("count after replace: %v", err)
	}
	if count != 1 {
		t.Fatalf("expected 1 ticket after replace, got %d", count)
	}
}

func TestValidateLookup(t *testing.T) {
	ticket := &tickets.Ticket{
		TicketID: "11111111-1111-1111-1111-111111111111",
		TripID:   "22222222-2222-2222-2222-222222222222",
		Status:   "ISSUED",
	}
	result, _ := tickets.ValidateLookup(ticket, ticket.TripID)
	if result != "BOARDED" {
		t.Fatalf("expected BOARDED, got %s", result)
	}

	ticket.Status = "USED"
	result, _ = tickets.ValidateLookup(ticket, ticket.TripID)
	if result != "ALREADY_USED" {
		t.Fatalf("expected ALREADY_USED, got %s", result)
	}
}
