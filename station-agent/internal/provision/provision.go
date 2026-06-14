package provision

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"github.com/transora/station-agent/internal/config"
	"gopkg.in/yaml.v3"
)

type provisionRequest struct {
	Code       string `json:"code"`
	AgentLabel string `json:"agentLabel,omitempty"`
}

type provisionResponse struct {
	StationID    string `json:"stationId"`
	Code         string `json:"code"`
	Name         string `json:"name"`
	ServiceToken string `json:"serviceToken"`
}

type persistedCredentials struct {
	StationID string `yaml:"station_id"`
	AuthToken string `yaml:"auth_token"`
}

// ApplyIfNeeded provisions the agent when registration_code is set and station_id/token are missing.
func ApplyIfNeeded(cfg *config.Config, configPath string) error {
	if cfg.Agent.StationID != "" && cfg.Core.AuthToken != "" {
		return nil
	}
	if cfg.Core.RegistrationCode == "" {
		return nil
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	result, err := callProvision(ctx, cfg.Core.HTTPURL, cfg.Core.RegistrationCode, cfg.Agent.Label)
	if err != nil {
		return err
	}

	cfg.Agent.StationID = result.StationID
	cfg.Core.AuthToken = result.ServiceToken
	cfg.Core.Login = ""
	cfg.Core.Password = ""

	if err := persistCredentials(configPath, result.StationID, result.ServiceToken); err != nil {
		return fmt.Errorf("persist credentials: %w", err)
	}

	return nil
}

func callProvision(ctx context.Context, httpURL, code, label string) (*provisionResponse, error) {
	body, _ := json.Marshal(provisionRequest{Code: code, AgentLabel: label})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, httpURL+"/api/stations/provision", bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")

	client := &http.Client{Timeout: 15 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("provision failed (%d): %s", resp.StatusCode, string(data))
	}

	var result provisionResponse
	if err := json.Unmarshal(data, &result); err != nil {
		return nil, err
	}
	if result.StationID == "" || result.ServiceToken == "" {
		return nil, fmt.Errorf("provision response missing stationId or serviceToken")
	}
	return &result, nil
}

func persistCredentials(configPath, stationID, token string) error {
	dir := filepath.Dir(configPath)
	if dir != "." && dir != "" {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return err
		}
	}
	statePath := filepath.Join(dir, "provisioned.yaml")
	payload := persistedCredentials{StationID: stationID, AuthToken: token}
	data, err := yaml.Marshal(payload)
	if err != nil {
		return err
	}
	return os.WriteFile(statePath, data, 0o600)
}

func LoadPersisted(configPath string, cfg *config.Config) {
	dir := filepath.Dir(configPath)
	statePath := filepath.Join(dir, "provisioned.yaml")
	data, err := os.ReadFile(statePath)
	if err != nil {
		return
	}
	var creds persistedCredentials
	if err := yaml.Unmarshal(data, &creds); err != nil {
		return
	}
	if cfg.Agent.StationID == "" && creds.StationID != "" {
		cfg.Agent.StationID = creds.StationID
	}
	if cfg.Core.AuthToken == "" && creds.AuthToken != "" {
		cfg.Core.AuthToken = creds.AuthToken
	}
}
