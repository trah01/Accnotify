package storage

import (
	"path/filepath"
	"testing"
)

func TestLegacyNullableMessagesAndScopedAck(t *testing.T) {
	path := filepath.Join(t.TempDir(), "legacy.db")
	s, err := NewSQLiteStorage(path)
	if err != nil {
		t.Fatal(err)
	}
	// Reproduce an older database before the image column was introduced.
	if _, err := s.db.Exec(`ALTER TABLE messages DROP COLUMN image`); err != nil {
		t.Fatal(err)
	}
	if _, err := s.db.Exec(`INSERT INTO devices(device_key) VALUES ('legacy')`); err != nil {
		t.Fatal(err)
	}
	if _, err := s.db.Exec(`INSERT INTO messages(device_id, message_id, body) VALUES (1, 'legacy-message', 'retained body')`); err != nil {
		t.Fatal(err)
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	s, err = NewSQLiteStorage(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	device, err := s.GetDeviceByKey("legacy")
	if err != nil || device == nil || device.PublicKey != "" || device.Name != "" {
		t.Fatalf("legacy device: %+v %v", device, err)
	}
	check := func() {
		t.Helper()
		messages, err := s.GetUndeliveredMessages(1)
		if err != nil || len(messages) != 1 {
			t.Fatalf("legacy pending: %d %v", len(messages), err)
		}
		if messages[0].Image != "" || messages[0].Body != "retained body" {
			t.Fatalf("legacy content changed: %+v", messages[0])
		}
		history, err := s.GetMessageHistory(1, 10, 0)
		if err != nil || len(history) != 1 || history[0].Image != "" {
			t.Fatalf("legacy history: %+v %v", history, err)
		}
	}
	check()
	if err := s.MarkMessageDelivered(2, "legacy-message"); err != nil {
		t.Fatal(err)
	}
	check()
	if err := s.MarkMessageDelivered(1, "legacy-message"); err != nil {
		t.Fatal(err)
	}
	messages, err := s.GetUndeliveredMessages(1)
	if err != nil || len(messages) != 0 {
		t.Fatalf("ACK not applied: %d %v", len(messages), err)
	}
	history, err := s.GetMessageHistory(1, 10, 0)
	if err != nil || len(history) != 0 {
		t.Fatalf("confirmed content retained: %+v %v", history, err)
	}
	var remaining int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM messages WHERE message_id = 'legacy-message'`).Scan(&remaining); err != nil || remaining != 0 {
		t.Fatalf("confirmed row retained: %d %v", remaining, err)
	}
}
