package consumer

import (
	"context"
	"fmt"

	"github.com/segmentio/kafka-go"
)

// Run reads messages from reader in a loop, calling handle for each one.
//
// Offset commit behaviour:
//   - If handle returns nil  → commit offset (message processed successfully).
//   - If handle returns err  → do NOT commit; message will be redelivered on
//     restart, giving at-least-once processing semantics. The caller is
//     responsible for logging the error before returning it (or returning nil
//     to skip unrecoverable messages such as malformed JSON).
//
// Run returns when ctx is cancelled or a fatal Kafka error occurs.
func Run(ctx context.Context, reader *kafka.Reader, handle func(kafka.Message) error) error {
	for {
		msg, err := reader.FetchMessage(ctx)
		if err != nil {
			return fmt.Errorf("fetch message: %w", err)
		}

		if err := handle(msg); err != nil {
			// Caller decided this message is worth retrying — do not commit.
			continue
		}

		if err := reader.CommitMessages(ctx, msg); err != nil {
			return fmt.Errorf("commit offset (topic=%s partition=%d offset=%d): %w",
				msg.Topic, msg.Partition, msg.Offset, err)
		}
	}
}
