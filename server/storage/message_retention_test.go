package storage

import (
	"path/filepath"
	"testing"
	"time"

	"github.com/accnotify/server/model"
)

func TestMessageExpiryAckAndCounters(t *testing.T) {
	s, err := NewSQLiteStorage(filepath.Join(t.TempDir(), "retention.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	now := time.Now()
	for _, id := range []string{"expired", "fresh", "acked"} {
		if err := s.CreateMessage(&model.Message{DeviceID: 1, MessageID: id, Body: "private content"}); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := s.db.Exec(`UPDATE messages SET created_at = ? WHERE message_id = 'expired'`, now.Add(-MessageRetention-time.Second)); err != nil {
		t.Fatal(err)
	}
	pending, err := s.GetUndeliveredMessages(1)
	if err != nil || len(pending) != 2 {
		t.Fatalf("expired message replayed: %d %v", len(pending), err)
	}
	if err := s.MarkMessageDelivered(2, "acked"); err != nil {
		t.Fatal(err)
	}
	if err := s.MarkMessageDelivered(1, "acked"); err != nil {
		t.Fatal(err)
	}
	if err := s.MarkMessageDelivered(1, "acked"); err != nil {
		t.Fatal(err)
	}
	if err := s.cleanupExpiredMessages(now); err != nil {
		t.Fatal(err)
	}
	var rows int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM messages`).Scan(&rows); err != nil || rows != 1 {
		t.Fatalf("content not deleted: %d %v", rows, err)
	}
	stats, err := s.DashboardStats(now)
	if err != nil || stats.Messages != 3 || stats.TodayMessages != 3 || stats.PendingMessages != 1 {
		t.Fatalf("deletion changed counters: %+v %v", stats, err)
	}
}

func TestRetentionUpgradePreservesCountsAndDevices(t *testing.T) {
	path := filepath.Join(t.TempDir(), "upgrade.db")
	s, err := NewSQLiteStorage(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.CreateDevice(&model.Device{DeviceKey: "existing"}); err != nil {
		t.Fatal(err)
	}
	// Reproduce a database from before aggregate counters existed.
	for _, query := range []string{`DROP TRIGGER count_received_push`, `DROP TABLE push_counts`,
		`INSERT INTO messages(device_id, message_id, body, created_at, delivered) VALUES (1, 'old', 'old private text', '2020-01-01 00:00:00', FALSE)`,
		`INSERT INTO messages(device_id, message_id, body, delivered) VALUES (1, 'confirmed', 'confirmed text', TRUE)`} {
		if _, err := s.db.Exec(query); err != nil {
			t.Fatal(err)
		}
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ {
		s, err = NewSQLiteStorage(path)
		if err != nil {
			t.Fatal(err)
		}
		stats, err := s.DashboardStats(time.Now())
		if err != nil || stats.Devices != 1 || stats.Messages != 2 || stats.PendingMessages != 0 {
			t.Fatalf("upgrade lost data or double-counted: %+v %v", stats, err)
		}
		var rows int
		if err := s.db.QueryRow(`SELECT COUNT(*) FROM messages`).Scan(&rows); err != nil || rows != 0 {
			t.Fatalf("legacy content retained: %d %v", rows, err)
		}
		if err := s.Close(); err != nil {
			t.Fatal(err)
		}
	}
}

func TestBackgroundExpiryCleanup(t *testing.T) {
	s, err := NewSQLiteStorage(filepath.Join(t.TempDir(), "background.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if _, err := s.db.Exec(`INSERT INTO messages(device_id, message_id, body, created_at) VALUES (1, 'expired', 'content', ?)`, time.Now().Add(-MessageRetention)); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(3 * time.Second)
	for {
		var rows int
		if err := s.db.QueryRow(`SELECT COUNT(*) FROM messages`).Scan(&rows); err != nil {
			t.Fatal(err)
		}
		if rows == 0 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("background cleanup did not delete expired content")
		}
		time.Sleep(20 * time.Millisecond)
	}
}
