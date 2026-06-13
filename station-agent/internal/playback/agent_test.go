package playback

import (
	"encoding/json"
	"testing"

	"github.com/transora/station-agent/internal/protocol"
)

func TestHandlePlayEnqueuesJob(t *testing.T) {
	agent := NewAgent()
	raw := json.RawMessage(`{
		"announcementId":"ann-1",
		"audioUrl":"/api/announcements/ann-1/audio",
		"priority":"HIGH",
		"text":"Test announcement"
	}`)

	if err := agent.HandlePlay(raw); err != nil {
		t.Fatalf("HandlePlay: %v", err)
	}
	if agent.QueueLen() != 1 {
		t.Fatalf("expected queue len 1, got %d", agent.QueueLen())
	}
}

func TestParseAudioPlayPayload(t *testing.T) {
	raw := json.RawMessage(`{
		"announcementId":"ann-2",
		"audioUrl":"/api/announcements/ann-2/audio",
		"priority":"MEDIUM",
		"text":"Departure soon"
	}`)
	payload, err := ParseAudioPlayPayload(raw)
	if err != nil {
		t.Fatalf("ParseAudioPlayPayload: %v", err)
	}
	if payload.AnnouncementID != "ann-2" {
		t.Fatalf("unexpected announcement id: %s", payload.AnnouncementID)
	}
	if payload.Priority != "MEDIUM" {
		t.Fatalf("unexpected priority: %s", payload.Priority)
	}
}

func TestAudioPlayMessageTypeConstant(t *testing.T) {
	if protocol.MsgAudioPlay != "audio.play" {
		t.Fatalf("unexpected audio play message type: %s", protocol.MsgAudioPlay)
	}
}
