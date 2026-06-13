package boarding

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

type Flusher struct {
	coreURL    string
	httpClient *http.Client
	batchSize  int
	getToken   func(ctx context.Context) (string, error)
}

func NewFlusher(coreURL string, batchSize int, getToken func(ctx context.Context) (string, error)) *Flusher {
	if batchSize <= 0 {
		batchSize = 100
	}
	return &Flusher{
		coreURL:    coreURL,
		httpClient: &http.Client{Timeout: 15 * time.Second},
		batchSize:  batchSize,
		getToken:   getToken,
	}
}

type syncEvent struct {
	TicketID      *string `json:"ticketId,omitempty"`
	TicketNumber  *string `json:"ticketNumber,omitempty"`
	TripID        *string `json:"tripId,omitempty"`
	ClientEventID *string `json:"clientEventId,omitempty"`
}

type syncRequest struct {
	Events []syncEvent `json:"events"`
}

func (f *Flusher) FlushToCore(ctx context.Context, buffer *Buffer) (int, error) {
	total := 0
	for {
		batch, err := buffer.ListPending(f.batchSize)
		if err != nil {
			return total, err
		}
		if len(batch) == 0 {
			return total, nil
		}
		token, err := f.getToken(ctx)
		if err != nil {
			return total, err
		}
		events := make([]syncEvent, 0, len(batch))
		ids := make([]int64, 0, len(batch))
		for _, rec := range batch {
			ticketID := rec.TicketID
			tripID := rec.TripID
			clientEventID := rec.ClientEventID
			events = append(events, syncEvent{
				TicketID:      &ticketID,
				TripID:        &tripID,
				ClientEventID: &clientEventID,
			})
			ids = append(ids, rec.ID)
		}
		body, _ := json.Marshal(syncRequest{Events: events})
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, f.coreURL+"/api/boarding/sync", bytes.NewReader(body))
		if err != nil {
			return total, err
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Authorization", "Bearer "+token)
		resp, err := f.httpClient.Do(req)
		if err != nil {
			return total, err
		}
		data, _ := io.ReadAll(resp.Body)
		_ = resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			return total, fmt.Errorf("boarding sync failed: %s", string(data))
		}
		if err := buffer.MarkSynced(ids); err != nil {
			return total, err
		}
		total += len(batch)
		if len(batch) < f.batchSize {
			return total, nil
		}
	}
}
