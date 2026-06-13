package mode

import (
	"sync"
	"time"
)

type StationMode string

const (
	Online   StationMode = "ONLINE"
	Degraded StationMode = "DEGRADED"
	Offline  StationMode = "OFFLINE"
)

type Event struct {
	Mode    StationMode `json:"mode"`
	Message string      `json:"message"`
	Since   time.Time   `json:"since"`
}

type subscription struct {
	ch chan Event
}

type Manager struct {
	mu            sync.RWMutex
	current       StationMode
	since         time.Time
	startedAt     time.Time
	subscribers   map[uint64]*subscription
	nextSubID     uint64
}

func NewManager() *Manager {
	now := time.Now()
	return &Manager{
		current:     Offline,
		since:       now,
		startedAt:   now,
		subscribers: make(map[uint64]*subscription),
	}
}

func MessageForMode(m StationMode) string {
	switch m {
	case Degraded:
		return "Связь нестабильна"
	case Offline:
		return "Нет связи с ядром"
	default:
		return ""
	}
}

func (m *Manager) Set(next StationMode) {
	m.mu.Lock()
	if m.current == next {
		m.mu.Unlock()
		return
	}
	m.current = next
	m.since = time.Now()
	event := Event{Mode: next, Message: MessageForMode(next), Since: m.since}
	subs := make([]*subscription, 0, len(m.subscribers))
	for _, sub := range m.subscribers {
		subs = append(subs, sub)
	}
	m.mu.Unlock()

	for _, sub := range subs {
		select {
		case sub.ch <- event:
		default:
		}
	}
}

func (m *Manager) Current() StationMode {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.current
}

func (m *Manager) Since() time.Time {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.since
}

func (m *Manager) CurrentEvent() Event {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return Event{Mode: m.current, Message: MessageForMode(m.current), Since: m.since}
}

func (m *Manager) UptimeSec() int64 {
	return int64(time.Since(m.startedAt).Seconds())
}

func (m *Manager) CoreConnected() bool {
	return m.Current() == Online
}

func (m *Manager) Subscribe() (events <-chan Event, unsubscribe func()) {
	ch := make(chan Event, 8)
	m.mu.Lock()
	id := m.nextSubID
	m.nextSubID++
	m.subscribers[id] = &subscription{ch: ch}
	m.mu.Unlock()

	return ch, func() {
		m.mu.Lock()
		delete(m.subscribers, id)
		close(ch)
		m.mu.Unlock()
	}
}
