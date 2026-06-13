package boarding_test

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"github.com/transora/station-agent/internal/boarding"
	"github.com/transora/station-agent/internal/mode"
	"github.com/transora/station-agent/internal/tickets"
)

func newOfflineHandler(t *testing.T, seeded []tickets.Ticket) *boarding.Handler {
	t.Helper()
	bufferPath := filepath.Join(t.TempDir(), "buffer.db")
	ticketPath := filepath.Join(t.TempDir(), "tickets.db")

	buf, err := boarding.OpenBuffer(bufferPath)
	if err != nil {
		t.Fatalf("open buffer: %v", err)
	}
	t.Cleanup(func() { _ = buf.Close() })

	cache, err := tickets.Open(ticketPath)
	if err != nil {
		t.Fatalf("open ticket cache: %v", err)
	}
	t.Cleanup(func() { _ = cache.Close() })

	if len(seeded) > 0 {
		if err := cache.UpsertBatch(seeded); err != nil {
			t.Fatalf("seed tickets: %v", err)
		}
	}

	modeManager := mode.NewManager()
	modeManager.Set(mode.Offline)

	return boarding.NewHandler(modeManager, buf, cache, nil, "http://127.0.0.1:1", func(ctx context.Context) (string, error) {
		return "", nil
	})
}

func TestOfflineScanBoardsIssuedTicket(t *testing.T) {
	ticketID := "11111111-1111-1111-1111-111111111111"
	tripID := "22222222-2222-2222-2222-222222222222"
	handler := newOfflineHandler(t, []tickets.Ticket{{
		TicketID:      ticketID,
		TicketNumber:  "T1-001",
		TripID:        tripID,
		Status:        "ISSUED",
		PassengerName: "Alice",
		SeatNumber:    7,
	}})

	body := map[string]string{
		"ticket_id":       ticketID,
		"trip_id":         tripID,
		"operator_id":     "inspector-1",
		"client_event_id": "evt-board-1",
	}
	payload, _ := json.Marshal(body)
	req := httptest.NewRequest(http.MethodPost, "/boarding/scan", bytes.NewReader(payload))
	rec := httptest.NewRecorder()
	handler.HandleScan(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}
	var resp map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if resp["scanResult"] != "BOARDED" {
		t.Fatalf("expected BOARDED, got %v", resp["scanResult"])
	}
	if resp["dataSource"] != "local_cache" {
		t.Fatalf("expected local_cache source, got %v", resp["dataSource"])
	}
}

func TestOfflineScanAlreadyUsed(t *testing.T) {
	ticketID := "11111111-1111-1111-1111-111111111111"
	tripID := "22222222-2222-2222-2222-222222222222"
	handler := newOfflineHandler(t, []tickets.Ticket{{
		TicketID: ticketID,
		TripID:   tripID,
		Status:   "USED",
	}})

	body := map[string]string{
		"ticket_id":       ticketID,
		"trip_id":         tripID,
		"client_event_id": "evt-used-1",
	}
	payload, _ := json.Marshal(body)
	req := httptest.NewRequest(http.MethodPost, "/boarding/scan", bytes.NewReader(payload))
	rec := httptest.NewRecorder()
	handler.HandleScan(rec, req)

	var resp map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if resp["scanResult"] != "ALREADY_USED" {
		t.Fatalf("expected ALREADY_USED, got %v", resp["scanResult"])
	}
}

func TestOfflineScanManifestNotLoaded(t *testing.T) {
	handler := newOfflineHandler(t, nil)
	body := map[string]string{
		"ticket_id":       "11111111-1111-1111-1111-111111111111",
		"trip_id":         "22222222-2222-2222-2222-222222222222",
		"client_event_id": "evt-missing-1",
	}
	payload, _ := json.Marshal(body)
	req := httptest.NewRequest(http.MethodPost, "/boarding/scan", bytes.NewReader(payload))
	rec := httptest.NewRecorder()
	handler.HandleScan(rec, req)

	var resp map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if resp["scanResult"] != "INVALID_TICKET" {
		t.Fatalf("expected INVALID_TICKET, got %v", resp["scanResult"])
	}
}

func TestOfflineScanWrongTrip(t *testing.T) {
	ticketID := "11111111-1111-1111-1111-111111111111"
	handler := newOfflineHandler(t, []tickets.Ticket{{
		TicketID: ticketID,
		TripID:   "22222222-2222-2222-2222-222222222222",
		Status:   "ISSUED",
	}})

	body := map[string]string{
		"ticket_id":       ticketID,
		"trip_id":         "33333333-3333-3333-3333-333333333333",
		"client_event_id": "evt-wrong-trip",
	}
	payload, _ := json.Marshal(body)
	req := httptest.NewRequest(http.MethodPost, "/boarding/scan", bytes.NewReader(payload))
	rec := httptest.NewRecorder()
	handler.HandleScan(rec, req)

	var resp map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if resp["scanResult"] != "WRONG_TRIP" {
		t.Fatalf("expected WRONG_TRIP, got %v", resp["scanResult"])
	}
}
