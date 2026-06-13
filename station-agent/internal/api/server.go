package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/transora/station-agent/internal/boarding"
	"github.com/transora/station-agent/internal/cache"
	"github.com/transora/station-agent/internal/config"
	"github.com/transora/station-agent/internal/mode"
	"github.com/transora/station-agent/internal/proxy"
)

type Server struct {
	cfg             config.Config
	mode            *mode.Manager
	cache           *cache.Cache
	coreProxy       *proxy.CoreProxy
	boardingHandler *boarding.Handler
}

func NewServer(
	cfg config.Config,
	modeManager *mode.Manager,
	tripCache *cache.Cache,
	coreProxy *proxy.CoreProxy,
	boardingHandler *boarding.Handler,
) *Server {
	return &Server{
		cfg:             cfg,
		mode:            modeManager,
		cache:           tripCache,
		coreProxy:       coreProxy,
		boardingHandler: boardingHandler,
	}
}

func (s *Server) Router() http.Handler {
	r := chi.NewRouter()
	r.Use(middleware.RequestID)
	r.Use(middleware.RealIP)
	r.Use(middleware.Logger)
	r.Use(middleware.Recoverer)

	r.Get("/agent/status", s.handleAgentStatus)
	r.Get("/agent/mode", s.handleAgentMode)
	r.Get("/agent/status/stream", s.handleAgentStatusStream)
	r.Get("/schedule/board", s.handleScheduleBoard)
	r.Get("/schedule/trips", s.handleScheduleTrips)
	r.Post("/boarding/scan", s.boardingHandler.HandleScan)
	r.Get("/boarding/buffer/status", s.boardingHandler.HandleBufferStatus)
	r.Get("/boarding/trips/{tripId}/passengers", s.boardingHandler.HandleTripPassengers)
	r.Post("/boarding/trips/{tripId}/sync", s.boardingHandler.HandleTripSync)

	r.NotFound(s.coreProxy.ServeHTTP)
	return r
}

func (s *Server) handleAgentStatus(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"stationId":     s.cfg.Agent.StationID,
		"mode":          s.mode.Current(),
		"cacheVersion":  s.cache.LastVersion(),
		"uptimeSec":     s.mode.UptimeSec(),
		"coreConnected": s.mode.CoreConnected(),
	})
}

func (s *Server) handleAgentMode(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"mode":  s.mode.Current(),
		"since": s.mode.Since().UTC().Format(time.RFC3339),
	})
}

func (s *Server) handleAgentStatusStream(w http.ResponseWriter, r *http.Request) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming unsupported", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")

	sendEvent := func(event mode.Event) {
		payload, _ := json.Marshal(map[string]any{
			"mode":    event.Mode,
			"message": event.Message,
			"since":   event.Since.UTC().Format(time.RFC3339),
		})
		_, _ = fmt.Fprintf(w, "data: %s\n\n", payload)
		flusher.Flush()
	}

	sendEvent(s.mode.CurrentEvent())
	events, unsubscribe := s.mode.Subscribe()
	defer unsubscribe()

	heartbeat := time.NewTicker(30 * time.Second)
	defer heartbeat.Stop()

	ctx := r.Context()
	for {
		select {
		case <-ctx.Done():
			return
		case event, open := <-events:
			if !open {
				return
			}
			sendEvent(event)
		case <-heartbeat.C:
			_, _ = fmt.Fprintf(w, ": heartbeat\n\n")
			flusher.Flush()
		}
	}
}

func (s *Server) handleScheduleBoard(w http.ResponseWriter, r *http.Request) {
	boardType := r.URL.Query().Get("type")
	if boardType == "" {
		boardType = "MAIN"
	}
	if boardType != "MAIN" {
		http.Error(w, "only MAIN board supported in skeleton", http.StatusBadRequest)
		return
	}
	board, err := s.cache.QueryBoardMain()
	if err != nil {
		http.Error(w, "cache error", http.StatusInternalServerError)
		return
	}
	if s.mode.Current() != mode.Online {
		w.Header().Set("X-Data-Source", "cache")
		w.Header().Set("X-Cache-Version", strconv.FormatInt(s.cache.LastVersion(), 10))
	}
	writeJSON(w, http.StatusOK, board)
}

func (s *Server) handleScheduleTrips(w http.ResponseWriter, r *http.Request) {
	date := r.URL.Query().Get("date")
	windowMin, _ := strconv.Atoi(r.URL.Query().Get("window_min"))
	trips, err := s.cache.ListTrips(date, windowMin)
	if err != nil {
		http.Error(w, "cache error", http.StatusInternalServerError)
		return
	}
	if s.mode.Current() != mode.Online {
		w.Header().Set("X-Data-Source", "cache")
	}
	writeJSON(w, http.StatusOK, map[string]any{"trips": trips})
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}
