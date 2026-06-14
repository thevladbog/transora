package main

import (
	"context"
	"encoding/json"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/transora/station-agent/internal/api"
	"github.com/transora/station-agent/internal/auth"
	"github.com/transora/station-agent/internal/boarding"
	"github.com/transora/station-agent/internal/cache"
	"github.com/transora/station-agent/internal/config"
	"github.com/transora/station-agent/internal/provision"
	"github.com/transora/station-agent/internal/core"
	"github.com/transora/station-agent/internal/mode"
	"github.com/transora/station-agent/internal/playback"
	"github.com/transora/station-agent/internal/protocol"
	"github.com/transora/station-agent/internal/tickets"
	"github.com/transora/station-agent/internal/proxy"
)

func main() {
	configPath := flag.String("config", "config.yaml", "path to config file")
	flag.Parse()

	cfg, err := config.Load(*configPath)
	if err != nil {
		log.Fatalf("load config: %v", err)
	}

	provision.LoadPersisted(*configPath, cfg)
	if err := provision.ApplyIfNeeded(cfg, *configPath); err != nil {
		log.Fatalf("provision: %v", err)
	}
	if cfg.Core.RegistrationCode != "" && cfg.Agent.StationID != "" {
		log.Printf("provisioned station %s", cfg.Agent.StationID)
	}

	tokenProvider := auth.NewTokenProvider(cfg.Core, cfg.Agent.StationID)
	if cfg.Core.AuthToken == "" {
		if err := tokenProvider.Login(context.Background()); err != nil {
			log.Fatalf("initial login: %v", err)
		}
		log.Printf("authenticated as %s", cfg.Core.Login)
	}

	modeManager := mode.NewManager()
	tripCache, err := cache.Open(cfg.ScheduleCache.DBPath, cache.SchemaSQL)
	if err != nil {
		log.Fatalf("open cache: %v", err)
	}
	defer tripCache.Close()

	boardingBuffer, err := boarding.OpenBuffer(cfg.Boarding.BufferDBPath)
	if err != nil {
		log.Fatalf("open boarding buffer: %v", err)
	}
	defer boardingBuffer.Close()

	ticketCache, err := tickets.Open(cfg.Boarding.TicketCacheDBPath)
	if err != nil {
		log.Fatalf("open ticket cache: %v", err)
	}
	defer ticketCache.Close()

	updater := cache.NewUpdater(tripCache)
	ticketUpdater := tickets.NewUpdater(ticketCache)
	playbackAgent := playback.NewAgent()
	messageHandler := &compositeHandler{
		schedule: updater,
		tickets:  ticketUpdater,
		playback: playbackAgent,
	}
	coreProxy, err := proxy.New(cfg.Core.HTTPURL, modeManager, tokenProvider)
	if err != nil {
		log.Fatalf("proxy: %v", err)
	}

	flusher := boarding.NewFlusher(cfg.Core.HTTPURL, cfg.Boarding.FlushBatchSize, tokenProvider.GetToken)
	ticketSyncer := tickets.NewSyncer(cfg.Core.HTTPURL, tokenProvider.GetToken)
	boardingHandler := boarding.NewHandler(
		modeManager,
		boardingBuffer,
		ticketCache,
		ticketSyncer,
		cfg.Core.HTTPURL,
		tokenProvider.GetToken,
	)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	onConnect := func(ctx context.Context) {
		count, err := flusher.FlushToCore(ctx, boardingBuffer)
		if err != nil {
			log.Printf("boarding flush failed: %v", err)
			return
		}
		if count > 0 {
			log.Printf("flushed %d boarding events to core", count)
		}
		if !cfg.Boarding.ShouldSyncTripsOnConnect() {
			return
		}
		go func() {
			syncCtx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
			defer cancel()
			tripIDs, err := tripCache.ListActiveTripIDs()
			if err != nil {
				log.Printf("ticket manifest trip list failed: %v", err)
				return
			}
			if len(tripIDs) == 0 {
				return
			}
			synced, err := ticketSyncer.SyncActiveTrips(syncCtx, ticketCache, tripIDs)
			if err != nil {
				log.Printf("ticket manifest sync failed: %v", err)
				return
			}
			log.Printf("synced %d tickets for %d active trips", synced, len(tripIDs))
		}()
	}

	conn := core.NewConnection(*cfg, modeManager, messageHandler, tripCache, tokenProvider, onConnect)
	go conn.Run(ctx)
	go runCleanup(ctx, tripCache, cfg.ScheduleCache.CleanupIntervalHours)

	server := api.NewServer(*cfg, modeManager, tripCache, coreProxy, boardingHandler)
	httpServer := &http.Server{
		Addr:              cfg.Agent.Listen,
		Handler:           server.Router(),
		ReadHeaderTimeout: 10 * time.Second,
	}

	go func() {
		log.Printf("station-agent listening on %s", cfg.Agent.Listen)
		if err := httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("http server: %v", err)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	<-stop
	cancel()
	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer shutdownCancel()
	_ = httpServer.Shutdown(shutdownCtx)
}

type compositeHandler struct {
	schedule *cache.Updater
	tickets  *tickets.Updater
	playback *playback.Agent
}

func (h *compositeHandler) HandleMessage(msgType string, raw json.RawMessage) error {
	if msgType == protocol.MsgAudioPlay {
		return h.playback.HandlePlay(raw)
	}
	if msgType == protocol.MsgAudioStop {
		return h.playback.HandleStop(raw)
	}
	if err := h.tickets.HandleMessage(msgType, raw); err != nil {
		return err
	}
	return h.schedule.HandleMessage(msgType, raw)
}

func runCleanup(ctx context.Context, tripCache *cache.Cache, intervalHours int) {
	ticker := time.NewTicker(time.Duration(intervalHours) * time.Hour)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := tripCache.CleanupOlderThan(24); err != nil {
				log.Printf("cache cleanup failed: %v", err)
			}
		}
	}
}
