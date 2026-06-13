package tickets

import (
	"encoding/json"

	"github.com/transora/station-agent/internal/protocol"
)

type Updater struct {
	cache *Cache
}

func NewUpdater(cache *Cache) *Updater {
	return &Updater{cache: cache}
}

func (u *Updater) HandleMessage(msgType string, raw json.RawMessage) error {
	switch msgType {
	case protocol.MsgTicketUsed, protocol.MsgTicketIssued, protocol.MsgTicketRefunded:
	default:
		return nil
	}
	var payload protocol.TicketEventPayload
	if err := json.Unmarshal(raw, &payload); err != nil {
		return err
	}
	return u.cache.UpsertFromEvent(Ticket{
		TicketID:      payload.TicketID,
		TicketNumber:  payload.TicketNumber,
		TripID:        payload.TripID,
		Status:        payload.Status,
		PassengerName: payload.PassengerName,
		SeatNumber:    payload.SeatNumber,
	})
}
