package consumer

import (
	"encoding/json"
	"log/slog"

	"github.com/segmentio/kafka-go"

	"github.com/example/indexer/internal/document"
)

// Handle processes a single product.changed message: unmarshals the event,
// builds the search document, and logs it.
func Handle(msg kafka.Message) error {
	var event document.ProductEvent
	if err := json.Unmarshal(msg.Value, &event); err != nil {
		// Malformed JSON cannot be fixed by retrying — log and skip.
		slog.Error("malformed event — skipping",
			slog.Int64("offset", msg.Offset),
			slog.Int("partition", msg.Partition),
			slog.String("error", err.Error()),
		)
		return nil
	}

	doc := document.Build(event)

	// In production this would be an HTTP/gRPC call to the search engine.
	slog.Info("index document",
		slog.String("event_id", event.EventID),
		slog.String("product_id", doc.ID),
		slog.String("country", doc.Country),
		slog.Int64("version", doc.Version),
		slog.Time("indexed_at", doc.IndexedAt),
	)
	return nil
}
