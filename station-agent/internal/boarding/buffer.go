package boarding

import (
	"database/sql"
	_ "embed"
	"fmt"
	"os"
	"path/filepath"
	"time"

	_ "github.com/mattn/go-sqlite3"
)

//go:embed schema.sql
var SchemaSQL string

type Buffer struct {
	db *sql.DB
}

type Record struct {
	ID            int64
	ClientEventID string
	TicketID      string
	TripID        string
	ScannedBy     string
	ScannedAt     time.Time
	Result        string
}

func OpenBuffer(dbPath string) (*Buffer, error) {
	if err := os.MkdirAll(filepath.Dir(dbPath), 0o755); err != nil {
		return nil, err
	}
	db, err := sql.Open("sqlite3", dbPath+"?_journal_mode=WAL&_synchronous=FULL")
	if err != nil {
		return nil, err
	}
	if _, err := db.Exec(SchemaSQL); err != nil {
		_ = db.Close()
		return nil, err
	}
	return &Buffer{db: db}, nil
}

func (b *Buffer) Close() error {
	return b.db.Close()
}

func (b *Buffer) Record(event Record) error {
	_, err := b.db.Exec(`
		INSERT INTO boarding_buffer (client_event_id, ticket_id, trip_id, scanned_by, scanned_at, result, synced)
		VALUES (?, ?, ?, ?, ?, ?, 0)
	`,
		event.ClientEventID,
		event.TicketID,
		event.TripID,
		event.ScannedBy,
		event.ScannedAt.UTC().Format(time.RFC3339),
		event.Result,
	)
	return err
}

func (b *Buffer) HasScannedTicket(ticketID string) (bool, error) {
	var count int
	err := b.db.QueryRow(`
		SELECT COUNT(*) FROM boarding_buffer
		WHERE ticket_id = ? AND synced = 0 AND result IN ('BOARDED', 'BUFFERED')
	`, ticketID).Scan(&count)
	return count > 0, err
}

func (b *Buffer) FindByClientEventID(clientEventID string) (*Record, error) {
	row := b.db.QueryRow(`
		SELECT id, client_event_id, ticket_id, trip_id, scanned_by, scanned_at, result
		FROM boarding_buffer WHERE client_event_id = ?
	`, clientEventID)
	return scanRecord(row)
}

func (b *Buffer) PendingCount() (int, error) {
	var count int
	err := b.db.QueryRow(`SELECT COUNT(*) FROM boarding_buffer WHERE synced = 0`).Scan(&count)
	return count, err
}

func (b *Buffer) LastSyncAt() (*time.Time, error) {
	var value sql.NullString
	err := b.db.QueryRow(`
		SELECT sync_at FROM boarding_buffer WHERE sync_at IS NOT NULL ORDER BY id DESC LIMIT 1
	`).Scan(&value)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if !value.Valid {
		return nil, nil
	}
	t, err := time.Parse(time.RFC3339, value.String)
	if err != nil {
		return nil, err
	}
	return &t, nil
}

func (b *Buffer) ListPending(limit int) ([]Record, error) {
	if limit <= 0 {
		limit = 100
	}
	rows, err := b.db.Query(`
		SELECT id, client_event_id, ticket_id, trip_id, scanned_by, scanned_at, result
		FROM boarding_buffer WHERE synced = 0 ORDER BY id ASC LIMIT ?
	`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]Record, 0)
	for rows.Next() {
		rec, err := scanRecordRows(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, *rec)
	}
	return out, rows.Err()
}

func (b *Buffer) MarkSynced(ids []int64) error {
	if len(ids) == 0 {
		return nil
	}
	now := time.Now().UTC().Format(time.RFC3339)
	tx, err := b.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, id := range ids {
		if _, err := tx.Exec(`UPDATE boarding_buffer SET synced = 1, sync_at = ? WHERE id = ?`, now, id); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func scanRecord(row *sql.Row) (*Record, error) {
	var rec Record
	var scannedAt string
	if err := row.Scan(&rec.ID, &rec.ClientEventID, &rec.TicketID, &rec.TripID, &rec.ScannedBy, &scannedAt, &rec.Result); err != nil {
		return nil, err
	}
	t, err := time.Parse(time.RFC3339, scannedAt)
	if err != nil {
		return nil, fmt.Errorf("parse scanned_at: %w", err)
	}
	rec.ScannedAt = t
	return &rec, nil
}

func scanRecordRows(rows *sql.Rows) (*Record, error) {
	var rec Record
	var scannedAt string
	if err := rows.Scan(&rec.ID, &rec.ClientEventID, &rec.TicketID, &rec.TripID, &rec.ScannedBy, &scannedAt, &rec.Result); err != nil {
		return nil, err
	}
	t, err := time.Parse(time.RFC3339, scannedAt)
	if err != nil {
		return nil, err
	}
	rec.ScannedAt = t
	return &rec, nil
}
