package storage

import (
	"database/sql"
	"time"

	"github.com/accnotify/server/model"
)

type DailyStats struct {
	Date     string `json:"date"`
	Messages int64  `json:"messages"`
	Devices  int64  `json:"devices"`
}

type Dashboard struct {
	Devices           int64        `json:"devices"`
	TodayDevices      int64        `json:"today_devices"`
	Messages          int64        `json:"messages"`
	TodayMessages     int64        `json:"today_messages"`
	YesterdayMessages int64        `json:"yesterday_messages"`
	PendingMessages   int64        `json:"pending_messages"`
	OnlineDevices     int          `json:"online_devices"`
	Days              []DailyStats `json:"days"`
	Timezone          string       `json:"timezone"`
}

// DashboardStats counts received pushes, not messages read by the recipient.
func (s *SQLiteStorage) DashboardStats(now time.Time) (*Dashboard, error) {
	zone := time.FixedZone("Asia/Shanghai", 8*60*60)
	now = now.In(zone)
	today := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, zone)
	start := today.UTC().Format(time.RFC3339)
	end := today.AddDate(0, 0, 1).UTC().Format(time.RFC3339)
	d := &Dashboard{Timezone: "Asia/Shanghai", Days: make([]DailyStats, 7)}
	if err := s.db.QueryRow(`SELECT COUNT(*), COALESCE(SUM(datetime(created_at) >= datetime(?) AND datetime(created_at) < datetime(?)), 0) FROM devices`, start, end).Scan(&d.Devices, &d.TodayDevices); err != nil {
		return nil, err
	}
	if err := s.db.QueryRow(`SELECT COALESCE(SUM(count), 0),
        COALESCE(SUM(CASE WHEN date = ? THEN count ELSE 0 END), 0),
        COALESCE(SUM(CASE WHEN date = ? THEN count ELSE 0 END), 0)
        FROM push_counts`, today.Format("2006-01-02"), today.AddDate(0, 0, -1).Format("2006-01-02")).Scan(&d.Messages, &d.TodayMessages, &d.YesterdayMessages); err != nil {
		return nil, err
	}
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM messages WHERE COALESCE(delivered, FALSE) = FALSE AND julianday(created_at) > julianday(?)`, now.Add(-MessageRetention)).Scan(&d.PendingMessages); err != nil {
		return nil, err
	}

	for i := range d.Days {
		d.Days[i].Date = today.AddDate(0, 0, i-6).Format("2006-01-02")
	}
	for _, table := range []string{"push_counts", "devices"} {
		query := `SELECT date, count FROM push_counts WHERE date >= ? AND date < ?`
		args := []interface{}{today.AddDate(0, 0, -6).Format("2006-01-02"), today.AddDate(0, 0, 1).Format("2006-01-02")}
		if table == "devices" {
			query = `SELECT date(created_at, '+8 hours'), COUNT(*) FROM ` + table + ` WHERE datetime(created_at) >= datetime(?) AND datetime(created_at) < datetime(?) GROUP BY date(created_at, '+8 hours')`
			args = []interface{}{today.AddDate(0, 0, -6).UTC().Format(time.RFC3339), end}
		}
		rows, err := s.db.Query(query, args...)
		if err != nil {
			return nil, err
		}
		for rows.Next() {
			var date string
			var count int64
			if err := rows.Scan(&date, &count); err != nil {
				rows.Close()
				return nil, err
			}
			for i := range d.Days {
				if d.Days[i].Date == date {
					if table == "push_counts" {
						d.Days[i].Messages = count
					} else {
						d.Days[i].Devices = count
					}
				}
			}
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return nil, err
		}
	}
	return d, nil
}

func (s *SQLiteStorage) ListDevices(query string, limit, offset int) ([]model.Device, int64, error) {
	var total int64
	// instr treats search input literally, including '%' and '_'.
	filter := ` WHERE ? = '' OR instr(lower(COALESCE(d.name, '')), lower(?)) > 0 OR CAST(d.id AS TEXT) = ? OR instr(lower(d.endpoint_id), lower(?)) > 0`
	from := ` FROM devices d LEFT JOIN device_groups m ON m.device_id=d.id LEFT JOIN push_groups g ON g.id=m.group_id`
	if err := s.db.QueryRow(`SELECT COUNT(*)`+from+filter, query, query, query, query).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.db.Query(`SELECT d.id, d.device_key, COALESCE(d.public_key, ''), COALESCE(d.name, ''), d.created_at, d.last_seen, d.platform, d.endpoint_id, COALESCE(g.id,0), COALESCE(g.push_key,'')`+from+filter+` ORDER BY d.id DESC LIMIT ? OFFSET ?`, query, query, query, query, limit, offset)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	devices := make([]model.Device, 0)
	for rows.Next() {
		var d model.Device
		if err := rows.Scan(&d.ID, &d.DeviceKey, &d.PublicKey, &d.Name, &d.CreatedAt, &d.LastSeen, &d.Platform, &d.EndpointID, &d.PushGroupID, &d.PushKey); err != nil {
			return nil, 0, err
		}
		devices = append(devices, d)
	}
	return devices, total, rows.Err()
}

func (s *SQLiteStorage) GetDeviceByID(id int64) (*model.Device, error) {
	var key string
	if err := s.db.QueryRow(`SELECT device_key FROM devices WHERE id = ?`, id).Scan(&key); err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	return s.GetDeviceByKey(key)
}

// DeviceIDs freezes recipients before a global push, so new registrations aren't included mid-send.
func (s *SQLiteStorage) DeviceIDs() ([]int64, error) {
	rows, err := s.db.Query(`SELECT id FROM devices ORDER BY id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	ids := make([]int64, 0)
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}
