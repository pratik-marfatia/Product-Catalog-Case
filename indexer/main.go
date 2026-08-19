package main

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"os"
	"os/signal"
	"strings"
	"syscall"

	"github.com/segmentio/kafka-go"

	"github.com/example/indexer/internal/consumer"
	"github.com/example/indexer/internal/document"
)

func main() {
	// Structured JSON to stdout — readable in docker compose logs and
	// ingestible by any log aggregator without a parsing step.
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		Level: slog.LevelInfo,
	}))
	slog.SetDefault(logger)

	brokers := strings.Split(getEnv("KAFKA_BOOTSTRAP_SERVERS", "localhost:19092"), ",")
	topic := getEnv("KAFKA_TOPIC", "product.changed")
	groupID := getEnv("KAFKA_GROUP_ID", "product-catalog-indexer")

	// GroupID + StartOffset: FirstOffset means:
	//   - First run: consume the topic from the beginning (enables full
	//     index rebuild by restarting with a fresh consumer group).
	//   - Subsequent runs: resume from the last committed offset.
	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers:     brokers,
		Topic:       topic,
		GroupID:     groupID,
		StartOffset: kafka.FirstOffset,
		MinBytes:    1,
		MaxBytes:    10 << 20, // 10 MB
	})
	defer reader.Close()

	// Honour SIGINT / SIGTERM for clean shutdown inside Docker.
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	slog.Info("indexer started",
		slog.Any("brokers", brokers),
		slog.String("topic", topic),
		slog.String("group", groupID),
	)

	err := consumer.Run(ctx, reader, func(msg kafka.Message) error {
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
		// For this exercise we log the document that would have been sent.
		slog.Info("index document",
			slog.String("event_id", event.EventID),
			slog.String("product_id", doc.ID),
			slog.Any("document", doc),
		)
		return nil
	})

	if err != nil && !errors.Is(err, context.Canceled) {
		slog.Error("consumer stopped with error", slog.String("error", err.Error()))
		os.Exit(1)
	}

	slog.Info("indexer stopped gracefully")
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
