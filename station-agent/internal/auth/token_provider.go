package auth

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"sync"
	"time"

	"github.com/transora/station-agent/internal/config"
)

type TokenProvider struct {
	cfg        config.CoreConfig
	stationID  string
	httpClient *http.Client

	mu           sync.Mutex
	accessToken  string
	refreshToken string
	expiresAt    time.Time
	ttlSeconds   int64
	staticToken  bool
}

type loginRequest struct {
	Login     string `json:"login"`
	Password  string `json:"password"`
	StationID string `json:"stationId"`
}

type refreshRequest struct {
	RefreshToken string `json:"refreshToken"`
	StationID    string `json:"stationId"`
}

type tokenResponse struct {
	AccessToken  string `json:"accessToken"`
	RefreshToken string `json:"refreshToken"`
	ExpiresIn    int64  `json:"expiresIn"`
}

func NewTokenProvider(cfg config.CoreConfig, stationID string) *TokenProvider {
	tp := &TokenProvider{
		cfg:       cfg,
		stationID: stationID,
		httpClient: &http.Client{Timeout: 10 * time.Second},
	}
	if cfg.AuthToken != "" {
		tp.accessToken = cfg.AuthToken
		tp.staticToken = true
	}
	return tp
}

func (p *TokenProvider) GetToken(ctx context.Context) (string, error) {
	if p.staticToken {
		return p.accessToken, nil
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.accessToken != "" && p.shouldRefreshLocked() {
		if err := p.refreshLocked(ctx); err != nil {
			if err := p.loginLocked(ctx); err != nil {
				return "", err
			}
		}
	}
	if p.accessToken == "" {
		if err := p.loginLocked(ctx); err != nil {
			return "", err
		}
	}
	return p.accessToken, nil
}

func (p *TokenProvider) Login(ctx context.Context) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.loginLocked(ctx)
}

func (p *TokenProvider) shouldRefreshLocked() bool {
	if p.expiresAt.IsZero() {
		return true
	}
	refreshAt := p.expiresAt.Add(-time.Duration(float64(p.ttlSeconds)*0.2) * time.Second)
	return time.Now().After(refreshAt)
}

func (p *TokenProvider) loginLocked(ctx context.Context) error {
	if p.cfg.Login == "" || p.cfg.Password == "" {
		return fmt.Errorf("core login and password required when auth_token is not set")
	}
	body, _ := json.Marshal(loginRequest{
		Login:     p.cfg.Login,
		Password:  p.cfg.Password,
		StationID: p.stationID,
	})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, p.cfg.HTTPURL+"/auth/login", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	return p.doTokenRequest(req)
}

func (p *TokenProvider) refreshLocked(ctx context.Context) error {
	if p.refreshToken == "" {
		return fmt.Errorf("no refresh token")
	}
	body, _ := json.Marshal(refreshRequest{
		RefreshToken: p.refreshToken,
		StationID:    p.stationID,
	})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, p.cfg.HTTPURL+"/auth/refresh", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	if err := p.doTokenRequest(req); err != nil {
		return err
	}
	return nil
}

func (p *TokenProvider) doTokenRequest(req *http.Request) error {
	resp, err := p.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("auth request failed: %s", string(data))
	}
	var tr tokenResponse
	if err := json.Unmarshal(data, &tr); err != nil {
		return err
	}
	p.accessToken = tr.AccessToken
	p.refreshToken = tr.RefreshToken
	p.ttlSeconds = tr.ExpiresIn
	if p.ttlSeconds == 0 {
		p.ttlSeconds = 900
	}
	p.expiresAt = time.Now().Add(time.Duration(p.ttlSeconds) * time.Second)
	return nil
}
