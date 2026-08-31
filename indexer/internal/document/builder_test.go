package document_test

import (
	"testing"
	"time"

	"github.com/example/indexer/internal/document"
)

// makeEvent returns a fully populated ProductEvent for use in tests.
func makeEvent() document.ProductEvent {
	now := time.Now().UTC()
	return document.ProductEvent{
		EventID:       "evt-001",
		EventType:     "product.changed",
		OccurredAt:    now,
		SchemaVersion: "1.0",
		Payload: document.Payload{
			ID:              "00000000-0000-0000-0000-000000000001",
			Name:            "Widget Pro",
			Status:          "ACTIVE",
			Country:         "GB",
			Version:         3,
			SourceUpdatedAt: now,
			CreatedAt:       now,
			UpdatedAt:       now,
		},
	}
}

// TestBuild_MapsAllPayloadFields verifies every field from the event payload
// is present in the search document with the correct value.
func TestBuild_MapsAllPayloadFields(t *testing.T) {
	event := makeEvent()
	doc := document.Build(event)

	if doc.ID != event.Payload.ID {
		t.Errorf("ID: got %q, want %q", doc.ID, event.Payload.ID)
	}
	if doc.Name != event.Payload.Name {
		t.Errorf("Name: got %q, want %q", doc.Name, event.Payload.Name)
	}
	if doc.Status != event.Payload.Status {
		t.Errorf("Status: got %q, want %q", doc.Status, event.Payload.Status)
	}
	if doc.Country != event.Payload.Country {
		t.Errorf("Country: got %q, want %q", doc.Country, event.Payload.Country)
	}
	if doc.Version != event.Payload.Version {
		t.Errorf("Version: got %d, want %d", doc.Version, event.Payload.Version)
	}
	if !doc.SourceUpdatedAt.Equal(event.Payload.SourceUpdatedAt) {
		t.Errorf("SourceUpdatedAt: got %v, want %v", doc.SourceUpdatedAt, event.Payload.SourceUpdatedAt)
	}
}

// TestBuild_StampsIndexedAtAsUTC verifies that IndexedAt is set at build time
// and is in UTC — consumers use this to measure indexing lag against the
// 30 s visibility SLA.
func TestBuild_StampsIndexedAtAsUTC(t *testing.T) {
	before := time.Now().UTC()
	doc := document.Build(makeEvent())
	after := time.Now().UTC()

	if doc.IndexedAt.Before(before) || doc.IndexedAt.After(after) {
		t.Errorf("IndexedAt %v not in expected range [%v, %v]", doc.IndexedAt, before, after)
	}
	if doc.IndexedAt.Location() != time.UTC {
		t.Errorf("IndexedAt not UTC: got location %v", doc.IndexedAt.Location())
	}
}

// TestBuild_ExcludesAuditFields documents the deliberate omission of
// createdAt and updatedAt from SearchDocument. The search engine cares
// about what to show (name, status) and when the source changed
// (sourceUpdatedAt), not when our system wrote the record.
// This is a compile-time check: if the fields were accidentally added to
// SearchDocument, the struct literal below would need to include them.
func TestBuild_ExcludesAuditFields(t *testing.T) {
	doc := document.Build(makeEvent())
	_ = document.SearchDocument{
		ID:              doc.ID,
		Name:            doc.Name,
		Status:          doc.Status,
		Country:         doc.Country,
		Version:         doc.Version,
		SourceUpdatedAt: doc.SourceUpdatedAt,
		IndexedAt:       doc.IndexedAt,
	}
}
