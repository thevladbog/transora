package proxy

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strings"

	"github.com/transora/station-agent/internal/mode"
)

type TokenProvider interface {
	GetToken(ctx context.Context) (string, error)
}

type CoreProxy struct {
	proxy  *httputil.ReverseProxy
	mode   *mode.Manager
	target *url.URL
	tokens TokenProvider
}

type errorResponse struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

func New(coreURL string, modeManager *mode.Manager, tokens TokenProvider) (*CoreProxy, error) {
	target, err := url.Parse(coreURL)
	if err != nil {
		return nil, err
	}
	proxy := httputil.NewSingleHostReverseProxy(target)
	p := &CoreProxy{proxy: proxy, mode: modeManager, target: target, tokens: tokens}
	proxy.Director = func(req *http.Request) {
		req.URL.Scheme = target.Scheme
		req.URL.Host = target.Host
		req.Host = target.Host
		req.Header.Set("X-Forwarded-By", "station-agent")
		if req.Header.Get("Authorization") == "" && p.tokens != nil {
			if token, err := p.tokens.GetToken(req.Context()); err == nil && token != "" {
				req.Header.Set("Authorization", "Bearer "+token)
			}
		}
	}
	proxy.ErrorHandler = func(w http.ResponseWriter, r *http.Request, err error) {
		writeJSON(w, http.StatusServiceUnavailable, errorResponse{
			Code:    "CORE_UNAVAILABLE",
			Message: "No connection to core system.",
		})
	}
	return p, nil
}

func (p *CoreProxy) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if p.mode.Current() == mode.Offline {
		writeJSON(w, http.StatusServiceUnavailable, errorResponse{
			Code:    "STATION_OFFLINE",
			Message: "Station is offline. Sales and API proxy unavailable.",
		})
		return
	}
	if strings.HasPrefix(r.URL.Path, "/agent/") ||
		strings.HasPrefix(r.URL.Path, "/schedule/") ||
		strings.HasPrefix(r.URL.Path, "/boarding/") {
		http.NotFound(w, r)
		return
	}
	p.proxy.ServeHTTP(w, r)
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}
