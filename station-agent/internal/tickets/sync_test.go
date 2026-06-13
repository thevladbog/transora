package tickets_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"github.com/transora/station-agent/internal/tickets"
)

func TestSyncTripLoadsManifest(t *testing.T) {
	tripID := "22222222-2222-2222-2222-222222222222"
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/boarding/trips/"+tripID+"/tickets" {
			t.Fatalf("unexpected path: %s", r.URL.Path)
		}
		if r.Header.Get("Authorization") != "Bearer test-token" {
			t.Fatalf("missing auth header")
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"tripId": tripID,
			"tickets": []map[string]any{
				{
					"ticketId":      "11111111-1111-1111-1111-111111111111",
					"ticketNumber":  "T1-001",
					"tripId":        tripID,
					"status":        "ISSUED",
					"passengerName": "Bob",
					"seatNumber":    5,
				},
			},
		})
	}))
	defer server.Close()

	dbPath := filepath.Join(t.TempDir(), "tickets.db")
	cache, err := tickets.Open(dbPath)
	if err != nil {
		t.Fatalf("open cache: %v", err)
	}
	defer cache.Close()

	syncer := tickets.NewSyncer(server.URL, func(ctx context.Context) (string, error) {
		return "test-token", nil
	})
	count, err := syncer.SyncTrip(context.Background(), cache, tripID)
	if err != nil {
		t.Fatalf("sync trip: %v", err)
	}
	if count != 1 {
		t.Fatalf("expected 1 ticket synced, got %d", count)
	}

	row, err := cache.GetByID("11111111-1111-1111-1111-111111111111")
	if err != nil {
		t.Fatalf("get ticket: %v", err)
	}
	if row.PassengerName != "Bob" {
		t.Fatalf("unexpected passenger: %s", row.PassengerName)
	}
}
