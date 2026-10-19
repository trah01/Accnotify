package storage

import (
	"github.com/accnotify/server/model"
	"time"
)

func (s *SQLiteStorage) CreateEndpointMessages(messages []*model.Message) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	now := time.Now()
	for _, msg := range messages {
		r, err := tx.Exec(`INSERT INTO messages (device_id,message_id,title,body,group_name,icon,url,image,sound,badge,encrypted_payload,created_at,delivered,notification_id,delete_requested) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`, msg.DeviceID, msg.MessageID, msg.Title, msg.Body, msg.Group, msg.Icon, msg.URL, msg.Image, msg.Sound, msg.Badge, msg.EncryptedPayload, now, false, msg.NotificationID, msg.DeleteRequested)
		if err != nil {
			return err
		}
		msg.ID, err = r.LastInsertId()
		if err != nil {
			return err
		}
		msg.CreatedAt = now
	}
	return tx.Commit()
}
