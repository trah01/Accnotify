package storage

import (
	"log"
	"time"
)

const MessageRetention = 5 * time.Minute

// Counters contain only a date and a quantity, never message or device details.
func (s *SQLiteStorage) initMessageRetention() error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var exists bool
	if err := tx.QueryRow(`SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'push_counts')`).Scan(&exists); err != nil {
		return err
	}
	if _, err := tx.Exec(`CREATE TABLE IF NOT EXISTS push_counts (date TEXT PRIMARY KEY, count INTEGER NOT NULL DEFAULT 0)`); err != nil {
		return err
	}
	if !exists {
		// Preserve historical totals before removing old message content on upgrade.
		if _, err := tx.Exec(`INSERT INTO push_counts(date, count) SELECT date(created_at, '+8 hours'), COUNT(*) FROM messages GROUP BY date(created_at, '+8 hours')`); err != nil {
			return err
		}
	}
	if _, err := tx.Exec(`CREATE TRIGGER IF NOT EXISTS count_received_push AFTER INSERT ON messages BEGIN
		INSERT INTO push_counts(date, count) VALUES (date(NEW.created_at, '+8 hours'), 1)
		ON CONFLICT(date) DO UPDATE SET count = count + 1;
	END`); err != nil {
		return err
	}
	if _, err := tx.Exec(`DELETE FROM messages WHERE COALESCE(delivered, FALSE) = TRUE OR julianday(created_at) <= julianday(?)`, time.Now().Add(-MessageRetention)); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *SQLiteStorage) cleanupExpiredMessages(now time.Time) error {
	if _, err := s.db.Exec(`DELETE FROM binding_requests WHERE expires_at <= ?`, now.Unix()); err != nil {
		return err
	}
	_, err := s.db.Exec(`DELETE FROM messages WHERE COALESCE(delivered, FALSE) = TRUE OR julianday(created_at) <= julianday(?)`, now.Add(-MessageRetention))
	return err
}

func (s *SQLiteStorage) startMessageCleanup() {
	s.cleanupStop = make(chan struct{})
	s.cleanupDone = make(chan struct{})
	go func() {
		defer close(s.cleanupDone)
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-s.cleanupStop:
				return
			case now := <-ticker.C:
				if err := s.cleanupExpiredMessages(now); err != nil {
					log.Printf("Message cleanup failed: %v", err)
				}
			}
		}
	}()
}
