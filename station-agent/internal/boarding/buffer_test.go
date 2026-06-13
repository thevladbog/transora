package boarding_test

import (
	"path/filepath"
	"testing"
	"time"

	"github.com/transora/station-agent/internal/boarding"
)

func TestBufferRecordAndPending(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "boarding.db")
	buf, err := boarding.OpenBuffer(dbPath)
	if err != nil {
		t.Fatalf("open buffer: %v", err)
	}
	defer buf.Close()

	if err := buf.Record(boarding.Record{
		ClientEventID: "evt-1",
		TicketID:      "ticket-1",
		TripID:        "trip-1",
		ScannedBy:     "inspector-1",
		ScannedAt:     time.Now().UTC(),
		Result:        "BUFFERED",
	}); err != nil {
		t.Fatalf("record: %v", err)
	}

	count, err := buf.PendingCount()
	if err != nil {
		t.Fatalf("pending count: %v", err)
	}
	if count != 1 {
		t.Fatalf("expected 1 pending, got %d", count)
	}

	pending, err := buf.ListPending(10)
	if err != nil {
		t.Fatalf("list pending: %v", err)
	}
	if len(pending) != 1 {
		t.Fatalf("expected 1 pending row")
	}

	if err := buf.MarkSynced([]int64{pending[0].ID}); err != nil {
		t.Fatalf("mark synced: %v", err)
	}
	count, err = buf.PendingCount()
	if err != nil {
		t.Fatalf("pending count after sync: %v", err)
	}
	if count != 0 {
		t.Fatalf("expected 0 pending after sync, got %d", count)
	}
}
