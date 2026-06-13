package cache

import (
	"database/sql"
	_ "embed"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	_ "github.com/mattn/go-sqlite3"
	"github.com/transora/station-agent/internal/protocol"
)

//go:embed schema.sql
var SchemaSQL string

type Cache struct {
	db *sql.DB
}

type BoardRow struct {
	TripID        string `json:"tripId"`
	Time          string `json:"time"`
	DisplayTime   string `json:"displayTime"`
	Destination   string `json:"destination"`
	Route         string `json:"route"`
	TripNumber    string `json:"tripNumber"`
	Platform      string `json:"platform,omitempty"`
	DisplayStatus string `json:"displayStatus"`
	DelayMinutes  int    `json:"delayMinutes"`
	Direction     string `json:"direction"`
	StopOrder     int    `json:"stopOrder,omitempty"`
}

type BoardResponse struct {
	StationCode string     `json:"stationCode"`
	Type        string     `json:"type"`
	GeneratedAt string     `json:"generatedAt"`
	Departures  []BoardRow `json:"departures"`
}

func Open(dbPath string, schemaSQL string) (*Cache, error) {
	if err := os.MkdirAll(filepath.Dir(dbPath), 0o755); err != nil {
		return nil, err
	}
	db, err := sql.Open("sqlite3", dbPath+"?_journal_mode=WAL&_synchronous=NORMAL")
	if err != nil {
		return nil, err
	}
	if _, err := db.Exec(schemaSQL); err != nil {
		_ = db.Close()
		return nil, err
	}
	return &Cache{db: db}, nil
}

func (c *Cache) Close() error {
	return c.db.Close()
}

func (c *Cache) LastVersion() int64 {
	var value sql.NullString
	err := c.db.QueryRow(`SELECT value FROM cache_meta WHERE key = 'last_version'`).Scan(&value)
	if err != nil || !value.Valid {
		return 0
	}
	var version int64
	fmt.Sscan(value.String, &version)
	return version
}

func (c *Cache) ApplySnapshot(snapshot protocol.SyncSnapshotPayload) error {
	tx, err := c.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	if _, err := tx.Exec(`DELETE FROM cached_trip`); err != nil {
		return err
	}
	for _, trip := range snapshot.Trips {
		if err := upsertTripTx(tx, trip); err != nil {
			return err
		}
	}
	if err := setMetaTx(tx, "last_version", fmt.Sprintf("%d", snapshot.Version)); err != nil {
		return err
	}
	if err := setMetaTx(tx, "last_sync_at", fmt.Sprintf("%d", time.Now().Unix())); err != nil {
		return err
	}
	if err := setMetaTx(tx, "station_id", snapshot.StationID); err != nil {
		return err
	}
	return tx.Commit()
}

func (c *Cache) UpsertTrip(trip protocol.TripPayload) error {
	tx, err := c.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err := upsertTripTx(tx, trip); err != nil {
		return err
	}
	if err := setMetaTx(tx, "last_version", fmt.Sprintf("%d", trip.Version)); err != nil {
		return err
	}
	return tx.Commit()
}

func (c *Cache) MarkCancelled(tripID string, version int64) error {
	_, err := c.db.Exec(
		`UPDATE cached_trip SET status = 'CANCELLED', version = ?, cached_at = ? WHERE trip_id = ?`,
		version, time.Now().Unix(), tripID,
	)
	return err
}

func (c *Cache) QueryBoardMain() (BoardResponse, error) {
	rows, err := c.db.Query(`
		SELECT trip_id, trip_number, route_name, display_time, direction_stop,
		       platform_number, delay_minutes, status, is_departure
		FROM cached_trip
		WHERE status NOT IN ('CANCELLED', 'COMPLETED')
		ORDER BY display_time
	`)
	if err != nil {
		return BoardResponse{}, err
	}
	defer rows.Close()

	departures := make([]BoardRow, 0)
	for rows.Next() {
		var tripID, tripNumber, routeName, displayTime, directionStop, status string
		var platform sql.NullInt64
		var delayMinutes int
		var isDeparture int
		if err := rows.Scan(&tripID, &tripNumber, &routeName, &displayTime, &directionStop, &platform, &delayMinutes, &status, &isDeparture); err != nil {
			return BoardResponse{}, err
		}
		if isDeparture == 0 {
			continue
		}
		platformLabel := ""
		if platform.Valid {
			platformLabel = fmt.Sprintf("Platform %d", platform.Int64)
		}
		departures = append(departures, BoardRow{
			TripID:        tripID,
			Time:          displayTime,
			DisplayTime:   displayTime,
			Destination:   directionStop,
			Route:         routeName,
			TripNumber:    tripNumber,
			Platform:      platformLabel,
			DisplayStatus: mapDisplayStatus(status, delayMinutes),
			DelayMinutes:  delayMinutes,
			Direction:     "DEPARTURE",
		})
	}
	stationID, _ := c.metaValue("station_id")
	return BoardResponse{
		StationCode: stationID,
		Type:        "MAIN",
		GeneratedAt: time.Now().UTC().Format(time.RFC3339),
		Departures:  departures,
	}, nil
}

func (c *Cache) ListTrips(date string, windowMin int) ([]BoardRow, error) {
	board, err := c.QueryBoardMain()
	if err != nil {
		return nil, err
	}
	if date == "" && windowMin == 0 {
		return board.Departures, nil
	}
	filtered := make([]BoardRow, 0, len(board.Departures))
	for _, row := range board.Departures {
		if date != "" && !strings.HasPrefix(row.DisplayTime, date) {
			continue
		}
		filtered = append(filtered, row)
	}
	return filtered, nil
}

func (c *Cache) CleanupOlderThan(hours int) error {
	cutoff := time.Now().Add(-time.Duration(hours) * time.Hour).Format("2006-01-02")
	_, err := c.db.Exec(`DELETE FROM cached_trip WHERE trip_date < ?`, cutoff)
	return err
}

func (c *Cache) ListActiveTripIDs() ([]string, error) {
	rows, err := c.db.Query(`
		SELECT trip_id FROM cached_trip
		WHERE status IN ('OPEN', 'BOARDING', 'PLANNED')
	`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	ids := make([]string, 0)
	for rows.Next() {
		var tripID string
		if err := rows.Scan(&tripID); err != nil {
			return nil, err
		}
		ids = append(ids, tripID)
	}
	return ids, rows.Err()
}

func (c *Cache) metaValue(key string) (string, error) {
	var value string
	err := c.db.QueryRow(`SELECT value FROM cache_meta WHERE key = ?`, key).Scan(&value)
	return value, err
}

func upsertTripTx(tx *sql.Tx, trip protocol.TripPayload) error {
	stopsJSON, err := json.Marshal(trip.Stops)
	if err != nil {
		return err
	}
	isDeparture := 0
	if trip.IsDeparture {
		isDeparture = 1
	}
	_, err = tx.Exec(`
		INSERT INTO cached_trip (
			trip_id, trip_number, trip_date, route_name, status, delay_minutes,
			stops_json, platform_number, is_departure, display_time, direction_stop,
			cached_at, version
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(trip_id) DO UPDATE SET
			trip_number = excluded.trip_number,
			trip_date = excluded.trip_date,
			route_name = excluded.route_name,
			status = excluded.status,
			delay_minutes = excluded.delay_minutes,
			stops_json = excluded.stops_json,
			platform_number = excluded.platform_number,
			is_departure = excluded.is_departure,
			display_time = excluded.display_time,
			direction_stop = excluded.direction_stop,
			cached_at = excluded.cached_at,
			version = excluded.version
	`,
		trip.TripID,
		trip.TripNumber,
		trip.TripDate,
		trip.RouteName,
		trip.Status,
		trip.DelayMinutes,
		string(stopsJSON),
		trip.PlatformNumber,
		isDeparture,
		trip.DisplayTime,
		trip.DirectionStop,
		time.Now().Unix(),
		trip.Version,
	)
	return err
}

func setMetaTx(tx *sql.Tx, key, value string) error {
	_, err := tx.Exec(`
		INSERT INTO cache_meta (key, value) VALUES (?, ?)
		ON CONFLICT(key) DO UPDATE SET value = excluded.value
	`, key, value)
	return err
}

func mapDisplayStatus(status string, delayMinutes int) string {
	switch status {
	case "BOARDING":
		return "BOARDING"
	case "DEPARTED", "IN_TRANSIT":
		return "DEPARTED"
	case "CANCELLED":
		return "CANCELLED"
	default:
		if delayMinutes > 0 {
			return "DELAYED"
		}
		return "SCHEDULED"
	}
}
