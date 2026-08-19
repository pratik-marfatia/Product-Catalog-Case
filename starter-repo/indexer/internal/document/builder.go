package document

import "time"

// Build constructs a SearchDocument from a ProductEvent.
//
// Field selection rationale:
//   - createdAt and updatedAt are omitted — the search engine cares about what
//     to show (name, status) and when the source changed, not when our system
//     touched the record.
//   - indexedAt is stamped here so the search engine (or a monitoring job) can
//     measure indexing lag (indexedAt - sourceUpdatedAt) and alert if the
//     30 s visibility SLA is at risk.
//   - version is included so the search engine can detect and discard stale
//     index updates if events arrive out of order (e.g. during a replay).
func Build(event ProductEvent) SearchDocument {
	return SearchDocument{
		ID:              event.Payload.ID,
		Name:            event.Payload.Name,
		Status:          event.Payload.Status,
		Country:         event.Payload.Country,
		Version:         event.Payload.Version,
		SourceUpdatedAt: event.Payload.SourceUpdatedAt,
		IndexedAt:       time.Now().UTC(),
	}
}
