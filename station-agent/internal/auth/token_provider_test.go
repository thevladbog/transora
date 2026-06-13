package auth_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/transora/station-agent/internal/auth"
	"github.com/transora/station-agent/internal/config"
)

func TestTokenProviderLoginAndRefresh(t *testing.T) {
	loginCalls := 0
	refreshCalls := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/auth/login":
			loginCalls++
			_ = json.NewEncoder(w).Encode(map[string]any{
				"accessToken":  "access-1",
				"refreshToken": "refresh-1",
				"expiresIn":    900,
			})
		case "/auth/refresh":
			refreshCalls++
			_ = json.NewEncoder(w).Encode(map[string]any{
				"accessToken":  "access-2",
				"refreshToken": "refresh-2",
				"expiresIn":    900,
			})
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer server.Close()

	tp := auth.NewTokenProvider(config.CoreConfig{
		HTTPURL:  server.URL,
		Login:    "station_agent",
		Password: "station_agent",
	}, "station-1")

	token, err := tp.GetToken(context.Background())
	if err != nil {
		t.Fatalf("get token: %v", err)
	}
	if token != "access-1" {
		t.Fatalf("expected access-1, got %s", token)
	}
	if loginCalls != 1 {
		t.Fatalf("expected 1 login call, got %d", loginCalls)
	}
}
