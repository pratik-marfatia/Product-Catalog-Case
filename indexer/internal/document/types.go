package document

import "time"

// ProductEvent is the envelope published by product-service to the
// product.changed Kafka topic.
// Field names are camelCase to match the JSON produced by the Java service.
type ProductEvent struct {
	EventID       string    `json:"eventId"`
	EventType     string    `json:"eventType"`
	OccurredAt    time.Time `json:"occurredAt"`
	SchemaVersion int       `json:"schemaVersion"`
	Payload       Payload   `json:"payload"`
}

// Payload mirrors the product record at the time the event was published.
type Payload struct {
	ID              string    `json:"id"`
	Name            string    `json:"name"`
	Status          string    `json:"status"`
	Country         string    `json:"country"`
	Version         int64     `json:"version"`
	SourceUpdatedAt time.Time `json:"sourceUpdatedAt"`
	CreatedAt       time.Time `json:"createdAt"`
	UpdatedAt       time.Time `json:"updatedAt"`
}

// SearchDocument is the document that would be sent to the search engine.
// It is a flattened, search-oriented projection of the product event.
type SearchDocument struct {
	ID              string    `json:"id"`
	Name            string    `json:"name"`
	Status          string    `json:"status"`
	Country         string    `json:"country"`
	Version         int64     `json:"version"`
	SourceUpdatedAt time.Time `json:"sourceUpdatedAt"`
	// IndexedAt marks when this document was built. The search engine (or ops)
	// can compute indexing lag as IndexedAt - SourceUpdatedAt to verify the
	// 30 s visibility SLA is being met.
	IndexedAt time.Time `json:"indexedAt"`
}
