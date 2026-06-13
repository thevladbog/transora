package boarding_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"github.com/transora/station-agent/internal/boarding"
)

func TestFlushToCore(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/boarding/sync" {
			t.Fatalf("unexpected path %s", r.URL.Path)
		}
		var body struct {
			Events []map[string]string `json:"events"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Fatalf("decode: %v", err)
		}
		if len(body.Events) != 1 {
			t.Fatalf("expected 1 event, got %d", len(body.Events))
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"batchId":"batch-1","results":[]}`))
	}))
	defer server.Close()

	dbPath := filepath.Join(t.TempDir(), "boarding.db")
	buf, err := boarding.OpenBuffer(dbPath)
	if err != nil {
		t.Fatalf("open buffer: %v", err)
	}
	defer buf.Close()

	if err := buf.Record(boarding.Record{
		ClientEventID: "evt-flush",
		TicketID:      "ticket-1",
		TripID:        "trip-1",
		ScannedBy:     "inspector",
		ScannedAt:     time.Now().UTC(),
		Result:        "BUFFERED",
	}); err != nil {
		t.Fatalf("record: %v", err)
	}

	flusher := boarding.NewFlusher(server.URL, 100, func(ctx context.Context) (string, error) {
		return "test-token", nil
	})
	count, err := flusher.FlushToCore(context.Background(), buf)
	if err != nil {
		t.Fatalf("flush: %v", err)
	}
	if count != 1 {
		t.Fatalf("expected 1 flushed, got %d", count)
	}
	pending, err := buf.PendingCount()
	if err != nil {
		t.Fatalf("pending: %v", err)
	}
	if pending != 0 {
		t.Fatalf("expected 0 pending after flush")
	}
}
