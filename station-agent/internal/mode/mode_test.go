package mode_test

import (
	"testing"
	"time"

	"github.com/transora/station-agent/internal/mode"
)

func TestModeTransitions(t *testing.T) {
	m := mode.NewManager()
	if m.Current() != mode.Offline {
		t.Fatalf("expected OFFLINE start, got %s", m.Current())
	}
	m.Set(mode.Online)
	if !m.CoreConnected() {
		t.Fatal("expected core connected in ONLINE mode")
	}
	m.Set(mode.Degraded)
	if m.CoreConnected() {
		t.Fatal("expected core disconnected in DEGRADED mode")
	}
}

func TestModeSubscriberReceivesBroadcast(t *testing.T) {
	m := mode.NewManager()
	events, unsubscribe := m.Subscribe()
	defer unsubscribe()

	done := make(chan mode.Event, 1)
	go func() {
		select {
		case event := <-events:
			done <- event
		case <-time.After(time.Second):
		}
	}()

	m.Set(mode.Online)
	select {
	case event := <-done:
		if event.Mode != mode.Online {
			t.Fatalf("expected ONLINE event, got %s", event.Mode)
		}
	case <-time.After(time.Second):
		t.Fatal("timeout waiting for mode event")
	}
}

func TestMessageForMode(t *testing.T) {
	if mode.MessageForMode(mode.Offline) == "" {
		t.Fatal("expected offline message")
	}
	if mode.MessageForMode(mode.Online) != "" {
		t.Fatal("expected empty online message")
	}
}
