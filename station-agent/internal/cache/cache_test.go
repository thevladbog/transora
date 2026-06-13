package cache_test

import (
	"path/filepath"
	"testing"

	"github.com/transora/station-agent/internal/cache"
	"github.com/transora/station-agent/internal/protocol"
)

func TestApplySnapshotAndQueryBoard(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "test.db")
	c, err := cache.Open(dbPath, cache.SchemaSQL)
	if err != nil {
		t.Fatalf("open cache: %v", err)
	}
	defer c.Close()

	platform := 2
	err = c.ApplySnapshot(protocol.SyncSnapshotPayload{
		StationID:   "00000000-0000-0000-0000-000000000001",
		GeneratedAt: "2026-06-13T10:00:00Z",
		Version:     100,
		Trips: []protocol.TripPayload{
			{
				TripID:        "trip-1",
				TripNumber:    "801",
				TripDate:      "2026-06-13",
				RouteName:     "801",
				Status:        "OPEN",
				DelayMinutes:  0,
				DisplayTime:   "2026-06-13T12:00:00Z",
				DirectionStop: "North Terminal",
				PlatformNumber: &platform,
				IsDeparture:   true,
				Version:       100,
			},
		},
	})
	if err != nil {
		t.Fatalf("apply snapshot: %v", err)
	}

	board, err := c.QueryBoardMain()
	if err != nil {
		t.Fatalf("query board: %v", err)
	}
	if len(board.Departures) != 1 {
		t.Fatalf("expected 1 departure, got %d", len(board.Departures))
	}
	if board.Departures[0].TripID != "trip-1" {
		t.Fatalf("unexpected trip id %s", board.Departures[0].TripID)
	}
}
