package storage

import (
	"database/sql"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/accnotify/server/model"
	_ "github.com/mattn/go-sqlite3"
)

// SQLiteStorage implements storage using SQLite
type SQLiteStorage struct {
	db          *sql.DB
	cleanupStop chan struct{}
	cleanupDone chan struct{}
	closeOnce   sync.Once
	closeErr    error
}

// NewSQLiteStorage creates a new SQLite storage instance
func NewSQLiteStorage(dbPath string) (*SQLiteStorage, error) {
	// Ensure directory exists
	dir := filepath.Dir(dbPath)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return nil, err
	}

	db, err := sql.Open("sqlite3", dbPath+"?_secure_delete=on&_busy_timeout=5000")
	if err != nil {
		return nil, err
	}

	storage := &SQLiteStorage{db: db}
	if err := storage.initTables(); err != nil {
		db.Close()
		return nil, err
	}
	if err := storage.initMessageRetention(); err != nil {
		db.Close()
		return nil, err
	}
	storage.startMessageCleanup()

	return storage, nil
}

// initTables creates the necessary tables
func (s *SQLiteStorage) initTables() error {
	queries := []string{
		`CREATE TABLE IF NOT EXISTS revoked_device_keys (key_hash BLOB PRIMARY KEY NOT NULL)`,
		`CREATE TABLE IF NOT EXISTS devices (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			device_key TEXT UNIQUE NOT NULL,
			public_key TEXT,
			name TEXT,
			created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
			last_seen DATETIME DEFAULT CURRENT_TIMESTAMP,
			platform TEXT NOT NULL DEFAULT 'mobile' CHECK(platform IN ('mobile','pc')),
			endpoint_id TEXT NOT NULL DEFAULT ''
		)`,
		`CREATE TABLE IF NOT EXISTS messages (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			device_id INTEGER NOT NULL,
			message_id TEXT UNIQUE NOT NULL,
			title TEXT,
			body TEXT,
			group_name TEXT,
			icon TEXT,
			url TEXT,
			image TEXT,
			sound TEXT,
			badge INTEGER DEFAULT 0,
			encrypted_payload BLOB,
			created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
			delivered BOOLEAN DEFAULT FALSE,
			FOREIGN KEY (device_id) REFERENCES devices(id)
		)`,
		`CREATE INDEX IF NOT EXISTS idx_messages_device_id ON messages(device_id)`,
		`CREATE INDEX IF NOT EXISTS idx_messages_created_at ON messages(created_at)`,
	}

	for _, query := range queries {
		if _, err := s.db.Exec(query); err != nil {
			return err
		}
	}

	// Migrate: add image column if not exists
	s.db.Exec(`ALTER TABLE messages ADD COLUMN image TEXT`)
	if err := s.ensureMessageOperationColumns(); err != nil {
		return err
	}

	return s.initMultiEndpoint()
}

// Close closes the database connection
func (s *SQLiteStorage) Close() error {
	s.closeOnce.Do(func() {
		close(s.cleanupStop)
		<-s.cleanupDone
		s.closeErr = s.db.Close()
	})
	return s.closeErr
}

// Device operations

// GetDeviceByKey retrieves a device by its key
func (s *SQLiteStorage) GetDeviceByKey(deviceKey string) (*model.Device, error) {
	device := &model.Device{}
	err := s.db.QueryRow(
		`SELECT id, device_key, COALESCE(public_key, ''), COALESCE(name, ''), created_at, last_seen, platform, endpoint_id
		 FROM devices WHERE device_key = ?`,
		deviceKey,
	).Scan(&device.ID, &device.DeviceKey, &device.PublicKey, &device.Name, &device.CreatedAt, &device.LastSeen, &device.Platform, &device.EndpointID)

	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return device, nil
}

// CreateDevice creates a new device
func (s *SQLiteStorage) CreateDevice(device *model.Device) error {
	device.Platform = devicePlatform(device.Platform)
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	endpoint, err := availableEndpointID(tx, device.Platform)
	if err != nil {
		return err
	}
	device.EndpointID = endpoint
	result, err := tx.Exec(`INSERT INTO devices(device_key,public_key,name,created_at,last_seen,platform,endpoint_id) SELECT ?,?,?,?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM revoked_device_keys WHERE key_hash=?)`, device.DeviceKey, device.PublicKey, device.Name, time.Now(), time.Now(), device.Platform, device.EndpointID, revokedKeyHash(device.DeviceKey))
	if err != nil {
		return err
	}
	n, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if n == 0 {
		return ErrRevokedDeviceKey
	}
	device.ID, err = result.LastInsertId()
	if err != nil {
		return err
	}
	group, err := tx.Exec(`INSERT INTO push_groups(push_key) VALUES(?)`, device.DeviceKey)
	if err != nil {
		return err
	}
	groupID, err := group.LastInsertId()
	if err != nil {
		return err
	}
	if _, err = tx.Exec(`INSERT INTO device_groups(device_id,group_id) VALUES(?,?)`, device.ID, groupID); err != nil {
		return err
	}
	return tx.Commit()
}

// UpdateDevicePublicKey updates the public key for a device
func (s *SQLiteStorage) UpdateDevicePublicKey(deviceKey, publicKey string) error {
	_, err := s.db.Exec(
		`UPDATE devices SET public_key = ?, last_seen = ? WHERE device_key = ?`,
		publicKey, time.Now(), deviceKey,
	)
	return err
}

// UpdateDeviceLastSeen updates the last seen timestamp
func (s *SQLiteStorage) UpdateDeviceLastSeen(deviceKey string) error {
	_, err := s.db.Exec(
		`UPDATE devices SET last_seen = ? WHERE device_key = ?`,
		time.Now(), deviceKey,
	)
	return err
}

// Message operations

// CreateMessage stores a new message
func (s *SQLiteStorage) CreateMessage(msg *model.Message) error {
	createdAt := time.Now()
	result, err := s.db.Exec(
		`INSERT INTO messages (device_id, message_id, title, body, group_name, icon, url, image, sound, badge, encrypted_payload, created_at, delivered, notification_id, delete_requested)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		msg.DeviceID, msg.MessageID, msg.Title, msg.Body, msg.Group, msg.Icon, msg.URL, msg.Image, msg.Sound, msg.Badge, msg.EncryptedPayload, createdAt, false, msg.NotificationID, msg.DeleteRequested,
	)
	if err != nil {
		return err
	}

	id, err := result.LastInsertId()
	if err != nil {
		return err
	}
	msg.ID = id
	msg.CreatedAt = createdAt
	return nil
}

// MarkMessageDelivered deletes content immediately after the recipient's ACK.
func (s *SQLiteStorage) MarkMessageDelivered(deviceID int64, messageID string) error {
	_, err := s.db.Exec(
		`DELETE FROM messages WHERE device_id = ? AND message_id = ?`,
		deviceID, messageID,
	)
	return err
}

// GetUndeliveredMessages retrieves undelivered messages for a device
func (s *SQLiteStorage) GetUndeliveredMessages(deviceID int64) ([]*model.Message, error) {
	rows, err := s.db.Query(
		`SELECT id, device_id, message_id, COALESCE(title, ''), COALESCE(body, ''), COALESCE(group_name, ''), COALESCE(icon, ''), COALESCE(url, ''), COALESCE(image, ''), COALESCE(sound, ''), COALESCE(badge, 0), encrypted_payload, created_at, COALESCE(delivered, FALSE), COALESCE(notification_id, ''), COALESCE(delete_requested, FALSE)
		 FROM messages
		 WHERE device_id = ? AND COALESCE(delivered, FALSE) = FALSE AND julianday(created_at) > julianday(?)
		 ORDER BY id ASC`,
		deviceID, time.Now().Add(-MessageRetention),
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var messages []*model.Message
	for rows.Next() {
		msg := &model.Message{}
		err := rows.Scan(
			&msg.ID, &msg.DeviceID, &msg.MessageID, &msg.Title, &msg.Body,
			&msg.Group, &msg.Icon, &msg.URL, &msg.Image, &msg.Sound, &msg.Badge,
			&msg.EncryptedPayload, &msg.CreatedAt, &msg.Delivered, &msg.NotificationID, &msg.DeleteRequested,
		)
		if err != nil {
			return nil, err
		}
		messages = append(messages, msg)
	}

	return messages, rows.Err()
}

// GetMessageHistory retrieves message history for a device
func (s *SQLiteStorage) GetMessageHistory(deviceID int64, limit, offset int) ([]*model.Message, error) {
	rows, err := s.db.Query(
		`SELECT id, device_id, message_id, COALESCE(title, ''), COALESCE(body, ''), COALESCE(group_name, ''), COALESCE(icon, ''), COALESCE(url, ''), COALESCE(image, ''), COALESCE(sound, ''), COALESCE(badge, 0), created_at, COALESCE(delivered, FALSE), COALESCE(notification_id, ''), COALESCE(delete_requested, FALSE)
		 FROM messages
		 WHERE device_id = ? AND COALESCE(delivered, FALSE) = FALSE AND julianday(created_at) > julianday(?)
		 ORDER BY created_at DESC
		 LIMIT ? OFFSET ?`,
		deviceID, time.Now().Add(-MessageRetention), limit, offset,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var messages []*model.Message
	for rows.Next() {
		msg := &model.Message{}
		err := rows.Scan(
			&msg.ID, &msg.DeviceID, &msg.MessageID, &msg.Title, &msg.Body,
			&msg.Group, &msg.Icon, &msg.URL, &msg.Image, &msg.Sound, &msg.Badge,
			&msg.CreatedAt, &msg.Delivered, &msg.NotificationID, &msg.DeleteRequested,
		)
		if err != nil {
			return nil, err
		}
		messages = append(messages, msg)
	}

	return messages, rows.Err()
}

// DeleteOldMessages deletes messages older than the specified duration
func (s *SQLiteStorage) DeleteOldMessages(olderThan time.Duration) (int64, error) {
	cutoff := time.Now().Add(-olderThan)
	result, err := s.db.Exec(
		`DELETE FROM messages WHERE created_at < ?`,
		cutoff,
	)
	if err != nil {
		return 0, err
	}
	return result.RowsAffected()
}
