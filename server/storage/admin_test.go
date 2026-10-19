package storage

import (
	"path/filepath"
	"testing"
	"time"
)

func TestDashboardShanghaiBoundariesAndExistingData(t *testing.T) {
	path := filepath.Join(t.TempDir(), "existing.db")
	s, err := NewSQLiteStorage(path)
	if err != nil {
		t.Fatal(err)
	}
	for i, timestamp := range []string{"2026-10-12 15:59:59", "2026-10-12 16:00:00", "2026-10-13 16:00:00"} {
		if _, err := s.db.Exec(`INSERT INTO devices(device_key, public_key, name, created_at, last_seen, endpoint_id) VALUES (?, '', 'existing', ?, ?, 'mobile-test-' || ?)`, i, timestamp, timestamp, i); err != nil {
			t.Fatal(err)
		}
	}
	for i, timestamp := range []string{"2026-10-12 15:59:59", "2026-10-12 16:00:00", "2026-10-13 10:00:00+08:00", "2026-10-13 16:00:00"} {
		if _, err := s.db.Exec(`INSERT INTO messages(device_id, message_id, created_at, delivered) VALUES (1, ?, ?, ?)`, i, timestamp, i%2 == 1); err != nil {
			t.Fatal(err)
		}
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	s, err = NewSQLiteStorage(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	d, err := s.DashboardStats(time.Date(2026, 10, 13, 6, 0, 0, 0, time.UTC))
	if err != nil {
		t.Fatal(err)
	}
	if d.Devices != 3 || d.TodayDevices != 1 || d.Messages != 4 || d.TodayMessages != 2 || d.YesterdayMessages != 1 || d.PendingMessages != 0 {
		t.Fatalf("incorrect day boundaries or data loss: %+v", d)
	}
	if len(d.Days) != 7 || d.Days[6].Date != "2026-10-13" || d.Days[6].Messages != 2 || d.Days[6].Devices != 1 {
		t.Fatalf("incorrect trend: %+v", d.Days)
	}
	devices, total, err := s.ListDevices("existing", 1, 1)
	if err != nil || total != 3 || len(devices) != 1 || devices[0].ID != 2 {
		t.Fatalf("incorrect pagination: %+v %d %v", devices, total, err)
	}
	devices, total, err = s.ListDevices("%", 10, 0)
	if err != nil || total != 0 || len(devices) != 0 {
		t.Fatal("search did not treat input literally")
	}
}
