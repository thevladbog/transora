package cache

import (
	"encoding/json"
	"log"

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
	case protocol.MsgSyncSnapshot:
		var snapshot protocol.SyncSnapshotPayload
		if err := json.Unmarshal(raw, &snapshot); err != nil {
			return err
		}
		return u.cache.ApplySnapshot(snapshot)
	case protocol.MsgTripCreated, protocol.MsgTripUpdated, protocol.MsgTripDelayed:
		var trip protocol.TripPayload
		if err := json.Unmarshal(raw, &trip); err != nil {
			return err
		}
		return u.cache.UpsertTrip(trip)
	case protocol.MsgTripCancelled:
		var trip protocol.TripPayload
		if err := json.Unmarshal(raw, &trip); err != nil {
			return err
		}
		version := trip.Version
		if version == 0 {
			version = u.cache.LastVersion()
		}
		return u.cache.MarkCancelled(trip.TripID, version)
	default:
		return nil
	}
}

func (u *Updater) HandleEnvelope(msg protocol.Message) error {
	raw, err := json.Marshal(msg.Payload)
	if err != nil {
		return err
	}
	if err := u.HandleMessage(msg.Type, raw); err != nil {
		log.Printf("cache update failed for %s: %v", msg.Type, err)
		return err
	}
	return nil
}
