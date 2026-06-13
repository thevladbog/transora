package tickets

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

type Cache struct {
	db *sql.DB
}

type Ticket struct {
	TicketID      string
	TicketNumber  string
	TripID        string
	Status        string
	PassengerName string
	SeatNumber    int
	CachedAt      time.Time
}

func Open(dbPath string) (*Cache, error) {
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
	return &Cache{db: db}, nil
}

func (c *Cache) Close() error {
	return c.db.Close()
}

func (c *Cache) UpsertBatch(tickets []Ticket) error {
	if len(tickets) == 0 {
		return nil
	}
	tx, err := c.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, ticket := range tickets {
		cachedAt := ticket.CachedAt
		if cachedAt.IsZero() {
			cachedAt = time.Now().UTC()
		}
		if _, err := tx.Exec(`
			INSERT INTO cached_ticket (
				ticket_id, ticket_number, trip_id, status, passenger_name, seat_number, cached_at
			) VALUES (?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT(ticket_id) DO UPDATE SET
				ticket_number = excluded.ticket_number,
				trip_id = excluded.trip_id,
				status = excluded.status,
				passenger_name = excluded.passenger_name,
				seat_number = excluded.seat_number,
				cached_at = excluded.cached_at
		`,
			ticket.TicketID,
			ticket.TicketNumber,
			ticket.TripID,
			ticket.Status,
			ticket.PassengerName,
			ticket.SeatNumber,
			cachedAt.Unix(),
		); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (c *Cache) ReplaceTrip(tripID string, tickets []Ticket) error {
	tx, err := c.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.Exec(`DELETE FROM cached_ticket WHERE trip_id = ?`, tripID); err != nil {
		return err
	}
	now := time.Now().UTC()
	for _, ticket := range tickets {
		cachedAt := ticket.CachedAt
		if cachedAt.IsZero() {
			cachedAt = now
		}
		if _, err := tx.Exec(`
			INSERT INTO cached_ticket (
				ticket_id, ticket_number, trip_id, status, passenger_name, seat_number, cached_at
			) VALUES (?, ?, ?, ?, ?, ?, ?)
		`,
			ticket.TicketID,
			ticket.TicketNumber,
			ticket.TripID,
			ticket.Status,
			ticket.PassengerName,
			ticket.SeatNumber,
			cachedAt.Unix(),
		); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (c *Cache) GetByID(ticketID string) (*Ticket, error) {
	row := c.db.QueryRow(`
		SELECT ticket_id, ticket_number, trip_id, status, passenger_name, seat_number, cached_at
		FROM cached_ticket WHERE ticket_id = ?
	`, ticketID)
	return scanTicket(row)
}

func (c *Cache) GetByNumber(ticketNumber string) (*Ticket, error) {
	row := c.db.QueryRow(`
		SELECT ticket_id, ticket_number, trip_id, status, passenger_name, seat_number, cached_at
		FROM cached_ticket WHERE ticket_number = ?
	`, ticketNumber)
	return scanTicket(row)
}

func (c *Cache) MarkUsed(ticketID string) error {
	_, err := c.db.Exec(`UPDATE cached_ticket SET status = 'USED' WHERE ticket_id = ?`, ticketID)
	return err
}

func (c *Cache) CountByTrip(tripID string) (int, error) {
	var count int
	err := c.db.QueryRow(`SELECT COUNT(*) FROM cached_ticket WHERE trip_id = ?`, tripID).Scan(&count)
	return count, err
}

func (c *Cache) ListByTrip(tripID string) ([]Ticket, error) {
	rows, err := c.db.Query(`
		SELECT ticket_id, ticket_number, trip_id, status, passenger_name, seat_number, cached_at
		FROM cached_ticket WHERE trip_id = ? ORDER BY seat_number ASC, ticket_number ASC
	`, tripID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]Ticket, 0)
	for rows.Next() {
		ticket, err := scanTicketRows(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, *ticket)
	}
	return out, rows.Err()
}

func (c *Cache) DeleteByTrip(tripID string) error {
	_, err := c.db.Exec(`DELETE FROM cached_ticket WHERE trip_id = ?`, tripID)
	return err
}

func scanTicket(row *sql.Row) (*Ticket, error) {
	var ticket Ticket
	var cachedAt int64
	if err := row.Scan(
		&ticket.TicketID,
		&ticket.TicketNumber,
		&ticket.TripID,
		&ticket.Status,
		&ticket.PassengerName,
		&ticket.SeatNumber,
		&cachedAt,
	); err != nil {
		return nil, err
	}
	ticket.CachedAt = time.Unix(cachedAt, 0).UTC()
	return &ticket, nil
}

func scanTicketRows(rows *sql.Rows) (*Ticket, error) {
	var ticket Ticket
	var cachedAt int64
	if err := rows.Scan(
		&ticket.TicketID,
		&ticket.TicketNumber,
		&ticket.TripID,
		&ticket.Status,
		&ticket.PassengerName,
		&ticket.SeatNumber,
		&cachedAt,
	); err != nil {
		return nil, err
	}
	ticket.CachedAt = time.Unix(cachedAt, 0).UTC()
	return &ticket, nil
}

func (c *Cache) UpsertFromScan(ticketID, tripID, status, passengerName string, seatNumber int) error {
	return c.UpsertFromEvent(Ticket{
		TicketID:      ticketID,
		TripID:        tripID,
		Status:        status,
		PassengerName: passengerName,
		SeatNumber:    seatNumber,
	})
}

func (c *Cache) UpsertFromEvent(ticket Ticket) error {
	if ticket.Status == "" {
		ticket.Status = "USED"
	}
	return c.UpsertBatch([]Ticket{ticket})
}

func ValidateLookup(ticket *Ticket, tripID string) (scanResult string, message string) {
	if ticket == nil {
		return "INVALID_TICKET", "Manifest not loaded"
	}
	if tripID != "" && ticket.TripID != tripID {
		return "WRONG_TRIP", "Ticket belongs to another trip"
	}
	switch ticket.Status {
	case "USED":
		return "ALREADY_USED", "Ticket already used"
	case "REFUNDED":
		return "REFUNDED", "Ticket was refunded"
	case "ISSUED":
		return "BOARDED", ""
	default:
		return "INVALID_TICKET", fmt.Sprintf("Unsupported ticket status %s", ticket.Status)
	}
}
