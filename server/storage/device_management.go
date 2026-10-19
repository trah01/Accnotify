package storage

import (
	"crypto/sha256"
	"database/sql"
	"errors"
	"time"
)

var ErrDeviceConflict = errors.New("device identity changed")
var ErrRevokedDeviceKey = errors.New("device key has been revoked")

func revokedKeyHash(key string) []byte { hash := sha256.Sum256([]byte(key)); return hash[:] }

// A device reset or removal affects only that physical member. Other members
// retain the shared public push ID and their independent RSA/ACK state.
func (s *SQLiteStorage) ManageDevice(key, expectedPublicKey, action, newKey, publicKey string) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var id int64
	var current string
	err = tx.QueryRow(`SELECT id,COALESCE(public_key,'') FROM devices WHERE device_key=?`, key).Scan(&id, &current)
	if err == sql.ErrNoRows {
		if action == "unregister" {
			return nil
		}
		if action == "reset_all" {
			var found string
			if tx.QueryRow(`SELECT public_key FROM devices WHERE device_key=?`, newKey).Scan(&found) == nil && found == publicKey {
				return nil
			}
		}
		return ErrDeviceConflict
	}
	if err != nil {
		return err
	}
	if action == "reset_rsa" && current == publicKey {
		return nil
	}
	if current != expectedPublicKey {
		return ErrDeviceConflict
	}
	var groupID int64
	if err = tx.QueryRow(`SELECT group_id FROM device_groups WHERE device_id=?`, id).Scan(&groupID); err != nil {
		return err
	}
	switch action {
	case "reset_rsa", "reset_all":
		if action == "reset_rsa" {
			newKey = key
		}
		var revoked bool
		if err = tx.QueryRow(`SELECT EXISTS(SELECT 1 FROM revoked_device_keys WHERE key_hash=?)`, revokedKeyHash(newKey)).Scan(&revoked); err != nil {
			return err
		}
		if revoked {
			return ErrRevokedDeviceKey
		}
		if _, err = tx.Exec(`UPDATE devices SET device_key=?,public_key=?,last_seen=? WHERE id=?`, newKey, publicKey, time.Now(), id); err != nil {
			return err
		}
		if action == "reset_all" {
			r, err := tx.Exec(`INSERT INTO push_groups(push_key) VALUES(?)`, newKey)
			if err != nil {
				return err
			}
			group, err := r.LastInsertId()
			if err != nil {
				return err
			}
			if _, err = tx.Exec(`UPDATE device_groups SET group_id=? WHERE device_id=?`, group, id); err != nil {
				return err
			}
		}
	case "unregister":
		for _, q := range []string{`DELETE FROM messages WHERE device_id=?`, `DELETE FROM device_groups WHERE device_id=?`, `DELETE FROM devices WHERE id=?`} {
			if _, err = tx.Exec(q, id); err != nil {
				return err
			}
		}
	default:
		return errors.New("unknown device action")
	}
	if action != "reset_rsa" {
		if _, err = tx.Exec(`INSERT OR IGNORE INTO revoked_device_keys(key_hash) VALUES(?)`, revokedKeyHash(key)); err != nil {
			return err
		}
		if _, err = tx.Exec(`DELETE FROM binding_requests WHERE pc_id=? OR mobile_id=?`, id, id); err != nil {
			return err
		}
		if _, err = tx.Exec(`DELETE FROM push_groups WHERE id=? AND NOT EXISTS(SELECT 1 FROM device_groups WHERE group_id=?)`, groupID, groupID); err != nil {
			return err
		}
	}
	return tx.Commit()
}
