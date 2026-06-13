package core

import (
	"context"
	"encoding/json"
	"log"
	"math/rand"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"github.com/transora/station-agent/internal/config"
	"github.com/transora/station-agent/internal/mode"
	"github.com/transora/station-agent/internal/protocol"
)

type MessageHandler interface {
	HandleMessage(msgType string, raw json.RawMessage) error
}

type VersionReader interface {
	LastVersion() int64
}

type TokenProvider interface {
	GetToken(ctx context.Context) (string, error)
}

type Connection struct {
	cfg       config.Config
	mode      *mode.Manager
	handler   MessageHandler
	versions  VersionReader
	tokens    TokenProvider
	onConnect func(ctx context.Context)
	dialer    *websocket.Dialer
	connMu    sync.Mutex
	conn      *websocket.Conn
	sendMu    sync.Mutex
}

func NewConnection(
	cfg config.Config,
	modeManager *mode.Manager,
	handler MessageHandler,
	versions VersionReader,
	tokens TokenProvider,
	onConnect func(ctx context.Context),
) *Connection {
	return &Connection{
		cfg:       cfg,
		mode:      modeManager,
		handler:   handler,
		versions:  versions,
		tokens:    tokens,
		onConnect: onConnect,
		dialer:    websocket.DefaultDialer,
	}
}

func (c *Connection) Run(ctx context.Context) {
	backoff := c.cfg.Core.ReconnectMin()
	offlineSince := time.Time{}
	for {
		select {
		case <-ctx.Done():
			return
		default:
		}

		c.mode.Set(mode.Degraded)
		conn, err := c.connect(ctx)
		if err != nil {
			log.Printf("core connection failed: %v", err)
			if offlineSince.IsZero() {
				offlineSince = time.Now()
			}
			if time.Since(offlineSince) >= c.cfg.Core.OfflineThreshold() {
				c.mode.Set(mode.Offline)
			}
			time.Sleep(jitter(backoff))
			backoff = minDuration(backoff*2, c.cfg.Core.ReconnectMax())
			continue
		}

		offlineSince = time.Time{}
		backoff = c.cfg.Core.ReconnectMin()
		c.mode.Set(mode.Online)
		c.requestSync()
		if c.onConnect != nil {
			go c.onConnect(ctx)
		}
		c.runSession(ctx, conn)
		log.Printf("core connection lost, reconnecting")
	}
}

func (c *Connection) connect(ctx context.Context) (*websocket.Conn, error) {
	token, err := c.tokens.GetToken(ctx)
	if err != nil {
		return nil, err
	}
	header := http.Header{}
	header.Set("Authorization", "Bearer "+token)
	header.Set("X-Station-ID", c.cfg.Agent.StationID)

	conn, _, err := c.dialer.DialContext(ctx, c.cfg.Core.WebSocketURL, header)
	if err != nil {
		return nil, err
	}
	c.connMu.Lock()
	c.conn = conn
	c.connMu.Unlock()
	return conn, nil
}

func (c *Connection) runSession(ctx context.Context, conn *websocket.Conn) {
	defer func() {
		c.connMu.Lock()
		if c.conn == conn {
			c.conn = nil
		}
		c.connMu.Unlock()
		_ = conn.Close()
	}()

	go c.heartbeatLoop(ctx)

	for {
		select {
		case <-ctx.Done():
			return
		default:
		}
		_ = conn.SetReadDeadline(time.Now().Add(c.cfg.Core.PingInterval() + c.cfg.Core.OfflineThreshold()))
		_, data, err := conn.ReadMessage()
		if err != nil {
			return
		}
		var msg protocol.Message
		if err := json.Unmarshal(data, &msg); err != nil {
			log.Printf("invalid core message: %v", err)
			continue
		}
		c.handleMessage(msg)
	}
}

func (c *Connection) handleMessage(msg protocol.Message) {
	switch msg.Type {
	case protocol.MsgPing:
		c.send(protocol.Message{Type: protocol.MsgPong, Payload: msg.Payload})
	case protocol.MsgSyncForce:
		c.requestSync()
	default:
		raw, err := json.Marshal(msg.Payload)
		if err != nil {
			return
		}
		if err := c.handler.HandleMessage(msg.Type, raw); err != nil {
			log.Printf("cache update error: %v", err)
		}
	}
}

func (c *Connection) requestSync() {
	payload := protocol.SyncRequestPayload{
		StationID:    c.cfg.Agent.StationID,
		HorizonHours: c.cfg.ScheduleCache.HorizonHours,
	}
	version := c.versions.LastVersion()
	if version > 0 {
		payload.CacheVersionFrom = &version
	}
	c.send(protocol.Message{Type: protocol.MsgSyncRequest, Payload: payload})
}

func (c *Connection) heartbeatLoop(ctx context.Context) {
	ticker := time.NewTicker(c.cfg.Core.PingInterval())
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if c.mode.Current() != mode.Online {
				continue
			}
			payload := protocol.AgentStatusPayload{
				StationID:     c.cfg.Agent.StationID,
				Mode:          string(c.mode.Current()),
				CacheVersion:  c.versions.LastVersion(),
				AudioQueueLen: 0,
				UptimeSec:     c.mode.UptimeSec(),
			}
			c.send(protocol.Message{Type: protocol.MsgAgentStatus, Payload: payload})
		}
	}
}

func (c *Connection) send(msg protocol.Message) {
	data, err := json.Marshal(msg)
	if err != nil {
		return
	}
	c.connMu.Lock()
	conn := c.conn
	c.connMu.Unlock()
	if conn == nil {
		return
	}
	c.sendMu.Lock()
	defer c.sendMu.Unlock()
	_ = conn.WriteMessage(websocket.TextMessage, data)
}

func jitter(d time.Duration) time.Duration {
	if d <= 0 {
		return time.Second
	}
	return d + time.Duration(rand.Int63n(int64(d/4+1)))
}

func minDuration(a, b time.Duration) time.Duration {
	if a < b {
		return a
	}
	return b
}
