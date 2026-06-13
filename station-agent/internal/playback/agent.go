package playback

import (
	"encoding/json"
	"log"
	"sync"

	"github.com/transora/station-agent/internal/protocol"
)

type Job struct {
	AnnouncementID string
	AudioURL       string
	Priority       string
	Text           string
}

type Agent struct {
	mu    sync.Mutex
	queue []Job
}

func NewAgent() *Agent {
	return &Agent{}
}

func (a *Agent) Enqueue(job Job) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.queue = append(a.queue, job)
}

func (a *Agent) QueueLen() int {
	a.mu.Lock()
	defer a.mu.Unlock()
	return len(a.queue)
}

func (a *Agent) HandlePlay(raw json.RawMessage) error {
	var payload protocol.AudioPlayPayload
	if err := json.Unmarshal(raw, &payload); err != nil {
		return err
	}
	a.Enqueue(Job{
		AnnouncementID: payload.AnnouncementID,
		AudioURL:       payload.AudioURL,
		Priority:       payload.Priority,
		Text:           payload.Text,
	})
	log.Printf("audio queued: announcement=%s url=%s", payload.AnnouncementID, payload.AudioURL)
	return nil
}

func ParseAudioPlayPayload(raw json.RawMessage) (protocol.AudioPlayPayload, error) {
	var payload protocol.AudioPlayPayload
	err := json.Unmarshal(raw, &payload)
	return payload, err
}
