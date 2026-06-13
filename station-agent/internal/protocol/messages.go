package protocol

type Message struct {
	Type    string      `json:"type"`
	Payload interface{} `json:"payload"`
}

const (
	MsgSyncRequest   = "sync.request"
	MsgSyncSnapshot  = "sync.snapshot"
	MsgTripCreated   = "trip.created"
	MsgTripUpdated   = "trip.updated"
	MsgTripCancelled = "trip.cancelled"
	MsgTripDelayed   = "trip.delay_updated"
	MsgPing          = "ping"
	MsgPong          = "pong"
	MsgSyncForce     = "sync.force"
	MsgAgentStatus   = "agent.status"
	MsgTicketUsed     = "ticket.used"
	MsgTicketIssued   = "ticket.issued"
	MsgTicketRefunded = "ticket.refunded"
	MsgAudioPlay      = "audio.play"
)

type SyncRequestPayload struct {
	StationID        string `json:"stationId"`
	CacheVersionFrom *int64 `json:"cacheVersionFrom,omitempty"`
	HorizonHours     int    `json:"horizonHours"`
}

type SyncSnapshotPayload struct {
	StationID   string        `json:"stationId"`
	GeneratedAt string        `json:"generatedAt"`
	Version     int64         `json:"version"`
	Trips       []TripPayload `json:"trips"`
}

type TripPayload struct {
	TripID         string        `json:"tripId"`
	TripNumber     string        `json:"tripNumber"`
	TripDate       string        `json:"tripDate"`
	RouteName      string        `json:"routeName"`
	Status         string        `json:"status"`
	DelayMinutes   int           `json:"delayMinutes"`
	DisplayTime    string        `json:"displayTime"`
	DirectionStop  string        `json:"directionStop"`
	PlatformNumber *int          `json:"platformNumber,omitempty"`
	IsDeparture    bool          `json:"isDeparture"`
	Version        int64         `json:"version"`
	Stops          []StopPayload `json:"stops"`
}

type StopPayload struct {
	StopOrder          int    `json:"stopOrder"`
	StopName           string `json:"stopName"`
	StopStatus         string `json:"stopStatus"`
	ScheduledDeparture string `json:"scheduledDeparture,omitempty"`
	ScheduledArrival   string `json:"scheduledArrival,omitempty"`
}

type AgentStatusPayload struct {
	StationID     string `json:"station_id"`
	Mode          string `json:"mode"`
	CacheVersion  int64  `json:"cache_version"`
	AudioQueueLen int    `json:"audio_queue_len"`
	UptimeSec     int64  `json:"uptime_sec"`
}

type TicketUsedPayload struct {
	TicketID      string `json:"ticketId"`
	TicketNumber  string `json:"ticketNumber"`
	TripID        string `json:"tripId"`
	Status        string `json:"status"`
	PassengerName string `json:"passengerName"`
	SeatNumber    int    `json:"seatNumber"`
	StationID     string `json:"stationId"`
	ScannedAt     string `json:"scannedAt"`
}

// TicketEventPayload is an alias for ticket lifecycle WS events (issued, used, refunded).
type TicketEventPayload = TicketUsedPayload

type AudioPlayPayload struct {
	AnnouncementID string `json:"announcementId"`
	AudioURL       string `json:"audioUrl"`
	Priority       string `json:"priority"`
	Text           string `json:"text"`
}
