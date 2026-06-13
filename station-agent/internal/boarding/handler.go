package boarding

import (
	"bytes"
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/transora/station-agent/internal/mode"
	"github.com/transora/station-agent/internal/tickets"
)

type scanRequest struct {
	TicketID       string `json:"ticket_id"`
	TicketID2      string `json:"ticketId"`
	TicketNumber   string `json:"ticket_number"`
	TicketNumber2  string `json:"ticketNumber"`
	TripID         string `json:"trip_id"`
	TripID2        string `json:"tripId"`
	OperatorID     string `json:"operator_id"`
	OperatorID2    string `json:"operatorId"`
	ClientEventID  string `json:"client_event_id"`
	ClientEventID2 string `json:"clientEventId"`
}

type scanResponse struct {
	TicketID         string `json:"ticketId"`
	TripID           string `json:"tripId"`
	ScanResult       string `json:"scanResult"`
	TicketStatus     string `json:"ticketStatus,omitempty"`
	AlreadyProcessed bool   `json:"alreadyProcessed,omitempty"`
	OfflineMode      bool   `json:"offlineMode,omitempty"`
	ClientEventID    string `json:"clientEventId,omitempty"`
	Message          string `json:"message,omitempty"`
	PassengerName    string `json:"passengerName,omitempty"`
	SeatNumber       int    `json:"seatNumber,omitempty"`
	DataSource       string `json:"dataSource,omitempty"`
}

type Handler struct {
	mode       *mode.Manager
	buffer     *Buffer
	tickets    *tickets.Cache
	syncer     *tickets.Syncer
	coreURL    string
	getToken   func(ctx context.Context) (string, error)
	httpClient *http.Client
}

func NewHandler(
	modeManager *mode.Manager,
	buffer *Buffer,
	ticketCache *tickets.Cache,
	syncer *tickets.Syncer,
	coreURL string,
	getToken func(ctx context.Context) (string, error),
) *Handler {
	return &Handler{
		mode:       modeManager,
		buffer:     buffer,
		tickets:    ticketCache,
		syncer:     syncer,
		coreURL:    coreURL,
		getToken:   getToken,
		httpClient: &http.Client{Timeout: 3 * time.Second},
	}
}

func (h *Handler) HandleScan(w http.ResponseWriter, r *http.Request) {
	var req scanRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	ticketRef := firstNonEmpty(req.TicketID, req.TicketID2, req.TicketNumber, req.TicketNumber2)
	tripID := firstNonEmpty(req.TripID, req.TripID2)
	operatorID := firstNonEmpty(req.OperatorID, req.OperatorID2)
	clientEventID := firstNonEmpty(req.ClientEventID, req.ClientEventID2)
	if clientEventID == "" {
		clientEventID = newEventID()
	}
	if ticketRef == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "ticket_id is required"})
		return
	}
	if operatorID == "" {
		operatorID = "station-agent"
	}

	current := h.mode.Current()
	if current == mode.Online || current == mode.Degraded {
		if resp, ok := h.proxyToCore(r, ticketRef, tripID, clientEventID); ok {
			h.cacheOnlineScan(resp)
			writeJSON(w, http.StatusOK, resp)
			return
		}
		if current == mode.Online {
			writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "core unavailable"})
			return
		}
	}

	if existing, err := h.buffer.FindByClientEventID(clientEventID); err == nil && existing != nil {
		writeJSON(w, http.StatusOK, scanResponse{
			TicketID:      existing.TicketID,
			TripID:        existing.TripID,
			ScanResult:    existing.Result,
			OfflineMode:   true,
			ClientEventID: existing.ClientEventID,
			DataSource:    "local_cache",
			Message:       "duplicate buffered event",
		})
		return
	}

	result, ticket, message := h.checkTicketLocally(ticketRef, tripID)
	if result == "INVALID_TICKET" {
		writeJSON(w, http.StatusOK, scanResponse{
			TicketID:    ticketRef,
			TripID:      tripID,
			ScanResult:  result,
			OfflineMode: true,
			DataSource:  "local_cache",
			Message:     message,
		})
		return
	}

	ticketID := ticketRef
	if ticket != nil {
		ticketID = ticket.TicketID
		if tripID == "" {
			tripID = ticket.TripID
		}
	}

	rec := Record{
		ClientEventID: clientEventID,
		TicketID:      ticketID,
		TripID:        tripID,
		ScannedBy:     operatorID,
		ScannedAt:     time.Now().UTC(),
		Result:        result,
	}
	if err := h.buffer.Record(rec); err != nil {
		http.Error(w, "buffer error", http.StatusInternalServerError)
		return
	}

	resp := scanResponse{
		TicketID:      ticketID,
		TripID:        tripID,
		ScanResult:    result,
		OfflineMode:   true,
		ClientEventID: clientEventID,
		DataSource:    "local_cache",
		Message:       message,
	}
	if ticket != nil {
		resp.PassengerName = ticket.PassengerName
		resp.SeatNumber = ticket.SeatNumber
		resp.TicketStatus = ticketStatusForResult(result, ticket.Status)
		resp.AlreadyProcessed = result == "ALREADY_USED"
	}
	writeJSON(w, http.StatusOK, resp)
}

func (h *Handler) HandleBufferStatus(w http.ResponseWriter, r *http.Request) {
	count, err := h.buffer.PendingCount()
	if err != nil {
		http.Error(w, "buffer error", http.StatusInternalServerError)
		return
	}
	lastSync, _ := h.buffer.LastSyncAt()
	payload := map[string]any{"pendingCount": count}
	if lastSync != nil {
		payload["lastSyncAt"] = lastSync.UTC().Format(time.RFC3339)
	}
	writeJSON(w, http.StatusOK, payload)
}

func (h *Handler) HandleTripPassengers(w http.ResponseWriter, r *http.Request) {
	tripID := chi.URLParam(r, "tripId")
	if tripID == "" {
		http.Error(w, "trip id required", http.StatusBadRequest)
		return
	}
	rows, err := h.tickets.ListByTrip(tripID)
	if err != nil {
		http.Error(w, "cache error", http.StatusInternalServerError)
		return
	}
	passengers := make([]map[string]any, 0, len(rows))
	for _, row := range rows {
		passengers = append(passengers, map[string]any{
			"ticketId":      row.TicketID,
			"ticketNumber":  row.TicketNumber,
			"tripId":        row.TripID,
			"status":        row.Status,
			"passengerName": row.PassengerName,
			"seatNumber":    row.SeatNumber,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"tripId":           tripID,
		"totalPassengers":  len(passengers),
		"passengers":       passengers,
		"source":           "cache",
	})
}

func (h *Handler) HandleTripSync(w http.ResponseWriter, r *http.Request) {
	if h.mode.Current() == mode.Offline {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{
			"error": "station offline; cannot sync manifest",
		})
		return
	}
	tripID := chi.URLParam(r, "tripId")
	if tripID == "" {
		http.Error(w, "trip id required", http.StatusBadRequest)
		return
	}
	count, err := h.syncer.SyncTrip(r.Context(), h.tickets, tripID)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"tripId":       tripID,
		"ticketCount":  count,
		"syncedAt":     time.Now().UTC().Format(time.RFC3339),
	})
}

func (h *Handler) checkTicketLocally(ticketRef, tripID string) (string, *tickets.Ticket, string) {
	if h.tickets == nil {
		return "INVALID_TICKET", nil, "Manifest not loaded"
	}
	ticket, err := h.lookupTicket(ticketRef)
	if err != nil {
		return "INVALID_TICKET", nil, "Manifest not loaded"
	}
	if ticket == nil {
		return "INVALID_TICKET", nil, "Manifest not loaded"
	}
	if tripID != "" && ticket.TripID != tripID {
		return "WRONG_TRIP", ticket, "Ticket belongs to another trip"
	}
	if ticket.Status == "USED" {
		return "ALREADY_USED", ticket, "Ticket already used"
	}
	if scanned, err := h.buffer.HasScannedTicket(ticket.TicketID); err == nil && scanned {
		return "ALREADY_USED", ticket, "Ticket already scanned offline"
	}
	result, message := tickets.ValidateLookup(ticket, tripID)
	if result == "BOARDED" {
		_ = h.tickets.MarkUsed(ticket.TicketID)
		ticket.Status = "USED"
	}
	return result, ticket, message
}

func (h *Handler) lookupTicket(ticketRef string) (*tickets.Ticket, error) {
	ticket, err := h.tickets.GetByID(ticketRef)
	if err == nil {
		return ticket, nil
	}
	if err != sql.ErrNoRows {
		return nil, err
	}
	ticket, err = h.tickets.GetByNumber(ticketRef)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	return ticket, err
}

func (h *Handler) cacheOnlineScan(resp scanResponse) {
	if h.tickets == nil || resp.TicketID == "" {
		return
	}
	status := resp.TicketStatus
	if status == "" {
		status = "USED"
	}
	_ = h.tickets.UpsertFromScan(resp.TicketID, resp.TripID, status, resp.PassengerName, resp.SeatNumber)
}

func (h *Handler) proxyToCore(r *http.Request, ticketRef, tripID, clientEventID string) (scanResponse, bool) {
	coreBody := map[string]string{
		"clientEventId": clientEventID,
	}
	if looksLikeUUID(ticketRef) {
		coreBody["ticketId"] = ticketRef
	} else {
		coreBody["ticketNumber"] = ticketRef
	}
	if tripID != "" {
		coreBody["tripId"] = tripID
	}
	body, _ := json.Marshal(coreBody)
	req, err := http.NewRequestWithContext(r.Context(), http.MethodPost, h.coreURL+"/api/boarding/scan", bytes.NewReader(body))
	if err != nil {
		return scanResponse{}, false
	}
	req.Header.Set("Content-Type", "application/json")
	if auth := r.Header.Get("Authorization"); auth != "" {
		req.Header.Set("Authorization", auth)
	} else {
		token, err := h.getToken(r.Context())
		if err != nil {
			return scanResponse{}, false
		}
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := h.httpClient.Do(req)
	if err != nil {
		return scanResponse{}, false
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	if err != nil || resp.StatusCode != http.StatusOK {
		return scanResponse{}, false
	}
	var out scanResponse
	if err := json.Unmarshal(data, &out); err != nil {
		return scanResponse{}, false
	}
	return out, true
}

func ticketStatusForResult(scanResult, cachedStatus string) string {
	switch scanResult {
	case "BOARDED":
		return "USED"
	case "ALREADY_USED":
		return "USED"
	default:
		return cachedStatus
	}
}

func looksLikeUUID(value string) bool {
	if len(value) != 36 {
		return false
	}
	for i, ch := range value {
		switch i {
		case 8, 13, 18, 23:
			if ch != '-' {
				return false
			}
		default:
			if (ch < '0' || ch > '9') && (ch < 'a' || ch > 'f') && (ch < 'A' || ch > 'F') {
				return false
			}
		}
	}
	return true
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

func newEventID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
