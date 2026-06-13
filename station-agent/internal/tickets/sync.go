package tickets

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

type Syncer struct {
	coreURL    string
	httpClient *http.Client
	getToken   func(ctx context.Context) (string, error)
}

func NewSyncer(coreURL string, getToken func(ctx context.Context) (string, error)) *Syncer {
	return &Syncer{
		coreURL:    coreURL,
		httpClient: &http.Client{Timeout: 30 * time.Second},
		getToken:   getToken,
	}
}

type manifestResponse struct {
	TripID  string         `json:"tripId"`
	Tickets []manifestRow  `json:"tickets"`
}

type manifestRow struct {
	TicketID      string `json:"ticketId"`
	TicketNumber  string `json:"ticketNumber"`
	TripID        string `json:"tripId"`
	Status        string `json:"status"`
	PassengerName string `json:"passengerName"`
	SeatNumber    int    `json:"seatNumber"`
}

func (s *Syncer) SyncTrip(ctx context.Context, cache *Cache, tripID string) (int, error) {
	token, err := s.getToken(ctx)
	if err != nil {
		return 0, err
	}
	req, err := http.NewRequestWithContext(
		ctx,
		http.MethodGet,
		s.coreURL+"/api/boarding/trips/"+tripID+"/tickets",
		nil,
	)
	if err != nil {
		return 0, err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := s.httpClient.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return 0, err
	}
	if resp.StatusCode != http.StatusOK {
		return 0, fmt.Errorf("manifest sync failed: %s", string(data))
	}
	var manifest manifestResponse
	if err := json.Unmarshal(data, &manifest); err != nil {
		return 0, err
	}
	tickets := make([]Ticket, 0, len(manifest.Tickets))
	for _, row := range manifest.Tickets {
		tickets = append(tickets, Ticket{
			TicketID:      row.TicketID,
			TicketNumber:  row.TicketNumber,
			TripID:        row.TripID,
			Status:        row.Status,
			PassengerName: row.PassengerName,
			SeatNumber:    row.SeatNumber,
		})
	}
	if err := cache.ReplaceTrip(tripID, tickets); err != nil {
		return 0, err
	}
	return len(tickets), nil
}

func (s *Syncer) SyncActiveTrips(ctx context.Context, cache *Cache, tripIDs []string) (int, error) {
	total := 0
	for _, tripID := range tripIDs {
		count, err := s.SyncTrip(ctx, cache, tripID)
		if err != nil {
			return total, fmt.Errorf("sync trip %s: %w", tripID, err)
		}
		total += count
	}
	return total, nil
}
