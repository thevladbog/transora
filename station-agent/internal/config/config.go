package config

import (
	"os"
	"time"

	"gopkg.in/yaml.v3"
)

type Config struct {
	Agent         AgentConfig         `yaml:"agent"`
	Core          CoreConfig          `yaml:"core"`
	ScheduleCache ScheduleCacheConfig `yaml:"schedule_cache"`
	Boarding      BoardingConfig      `yaml:"boarding"`
}

type AgentConfig struct {
	StationID string `yaml:"station_id"`
	Listen    string `yaml:"listen"`
	LogLevel  string `yaml:"log_level"`
	Label     string `yaml:"label"`
}

type CoreConfig struct {
	WebSocketURL        string `yaml:"websocket_url"`
	HTTPURL             string `yaml:"http_url"`
	AuthToken           string `yaml:"auth_token"`
	Login               string `yaml:"login"`
	Password            string `yaml:"password"`
	RegistrationCode    string `yaml:"registration_code"`
	PingIntervalSec     int    `yaml:"ping_interval_sec"`
	PingTimeoutSec      int    `yaml:"ping_timeout_sec"`
	ReconnectMinSec     int    `yaml:"reconnect_min_sec"`
	ReconnectMaxSec     int    `yaml:"reconnect_max_sec"`
	OfflineThresholdSec int    `yaml:"offline_threshold_sec"`
}

type ScheduleCacheConfig struct {
	DBPath               string `yaml:"db_path"`
	HorizonHours         int    `yaml:"horizon_hours"`
	CleanupIntervalHours int    `yaml:"cleanup_interval_hours"`
}

type BoardingConfig struct {
	BufferDBPath       string `yaml:"buffer_db_path"`
	TicketCacheDBPath  string `yaml:"ticket_cache_db_path"`
	FlushBatchSize     int    `yaml:"flush_batch_size"`
	SyncTripsOnConnect *bool  `yaml:"sync_trips_on_connect"`
}

func (b BoardingConfig) ShouldSyncTripsOnConnect() bool {
	if b.SyncTripsOnConnect == nil {
		return true
	}
	return *b.SyncTripsOnConnect
}

func Load(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var cfg Config
	if err := yaml.Unmarshal(data, &cfg); err != nil {
		return nil, err
	}
	applyEnv(&cfg)
	applyDefaults(&cfg)
	return &cfg, nil
}

func applyEnv(cfg *Config) {
	if v := os.Getenv("STATION_AGENT_TOKEN"); v != "" {
		cfg.Core.AuthToken = v
	}
	if v := os.Getenv("STATION_ID"); v != "" {
		cfg.Agent.StationID = v
	}
	if v := os.Getenv("CORE_HTTP_URL"); v != "" {
		cfg.Core.HTTPURL = v
	}
	if v := os.Getenv("CORE_WS_URL"); v != "" {
		cfg.Core.WebSocketURL = v
	}
	if v := os.Getenv("AGENT_LISTEN"); v != "" {
		cfg.Agent.Listen = v
	}
	if v := os.Getenv("SCHEDULE_CACHE_DB"); v != "" {
		cfg.ScheduleCache.DBPath = v
	}
	if v := os.Getenv("CORE_LOGIN"); v != "" {
		cfg.Core.Login = v
	}
	if v := os.Getenv("CORE_PASSWORD"); v != "" {
		cfg.Core.Password = v
	}
	if v := os.Getenv("REGISTRATION_CODE"); v != "" {
		cfg.Core.RegistrationCode = v
	}
	if v := os.Getenv("BOARDING_BUFFER_DB"); v != "" {
		cfg.Boarding.BufferDBPath = v
	}
	if v := os.Getenv("TICKET_CACHE_DB"); v != "" {
		cfg.Boarding.TicketCacheDBPath = v
	}
}

func applyDefaults(cfg *Config) {
	if cfg.Agent.Listen == "" {
		cfg.Agent.Listen = ":8081"
	}
	if cfg.Core.PingIntervalSec == 0 {
		cfg.Core.PingIntervalSec = 30
	}
	if cfg.Core.PingTimeoutSec == 0 {
		cfg.Core.PingTimeoutSec = 10
	}
	if cfg.Core.ReconnectMinSec == 0 {
		cfg.Core.ReconnectMinSec = 1
	}
	if cfg.Core.ReconnectMaxSec == 0 {
		cfg.Core.ReconnectMaxSec = 60
	}
	if cfg.Core.OfflineThresholdSec == 0 {
		cfg.Core.OfflineThresholdSec = 30
	}
	if cfg.ScheduleCache.HorizonHours == 0 {
		cfg.ScheduleCache.HorizonHours = 48
	}
	if cfg.ScheduleCache.CleanupIntervalHours == 0 {
		cfg.ScheduleCache.CleanupIntervalHours = 1
	}
	if cfg.ScheduleCache.DBPath == "" {
		cfg.ScheduleCache.DBPath = "./data/schedule_cache.db"
	}
	if cfg.Boarding.BufferDBPath == "" {
		cfg.Boarding.BufferDBPath = "./data/boarding_buffer.db"
	}
	if cfg.Boarding.FlushBatchSize == 0 {
		cfg.Boarding.FlushBatchSize = 100
	}
	if cfg.Boarding.TicketCacheDBPath == "" {
		cfg.Boarding.TicketCacheDBPath = "./data/ticket_cache.db"
	}
	if cfg.Core.Login == "" {
		cfg.Core.Login = "station_agent"
	}
}

func (c CoreConfig) PingInterval() time.Duration {
	return time.Duration(c.PingIntervalSec) * time.Second
}

func (c CoreConfig) OfflineThreshold() time.Duration {
	return time.Duration(c.OfflineThresholdSec) * time.Second
}

func (c CoreConfig) ReconnectMin() time.Duration {
	return time.Duration(c.ReconnectMinSec) * time.Second
}

func (c CoreConfig) ReconnectMax() time.Duration {
	return time.Duration(c.ReconnectMaxSec) * time.Second
}
