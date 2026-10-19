package storage

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"errors"
	"math/big"
	"strings"
	"time"

	"github.com/accnotify/server/model"
)

var ErrBinding = errors.New("绑定请求无效、已过期或验证码错误")
var ErrBindingRate = errors.New("验证码发送过于频繁，请稍后重试")
var ErrNoTarget = errors.New("指定的接收设备不存在，或推送地址已失效")

func devicePlatform(p string) string {
	if p == "pc" {
		return "pc"
	}
	return "mobile"
}
func newEndpointID(platform string) (string, error) {
	id, err := rand.Int(rand.Reader, big.NewInt(2176782336)) // 36^6
	if err != nil {
		return "", err
	}
	suffix := id.Text(36)
	return devicePlatform(platform) + "-" + strings.Repeat("0", 6-len(suffix)) + suffix, nil
}

func availableEndpointID(db interface {
	QueryRow(string, ...any) *sql.Row
}, platform string) (string, error) {
	for attempt := 0; attempt < 10; attempt++ {
		id, err := newEndpointID(platform)
		if err != nil {
			return "", err
		}
		var exists bool
		if err = db.QueryRow(`SELECT EXISTS(SELECT 1 FROM devices WHERE endpoint_id=?)`, id).Scan(&exists); err != nil {
			return "", err
		}
		if !exists {
			return id, nil
		}
	}
	return "", errors.New("生成设备标识失败，请重试")
}

func (s *SQLiteStorage) initMultiEndpoint() error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err := ensureDeviceColumns(tx); err != nil {
		return err
	}
	for _, q := range []string{
		`CREATE TABLE IF NOT EXISTS push_groups(id INTEGER PRIMARY KEY AUTOINCREMENT,push_key TEXT UNIQUE NOT NULL)`,
		`CREATE TABLE IF NOT EXISTS device_groups(device_id INTEGER PRIMARY KEY,group_id INTEGER NOT NULL)`,
		`CREATE INDEX IF NOT EXISTS idx_device_group ON device_groups(group_id)`,
		`CREATE TABLE IF NOT EXISTS binding_requests(id TEXT PRIMARY KEY,pc_id INTEGER NOT NULL,mobile_id INTEGER NOT NULL,group_id INTEGER NOT NULL,credential_hash BLOB NOT NULL,code_hash BLOB NOT NULL,expires_at INTEGER NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,consumed INTEGER NOT NULL DEFAULT 0,result_key TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL)`,
		`CREATE INDEX IF NOT EXISTS idx_binding_request_created ON binding_requests(created_at)`,
	} {
		if _, err := tx.Exec(q); err != nil {
			return err
		}
	}
	if err := backfillDeviceEndpoints(tx); err != nil {
		return err
	}
	for _, q := range []string{
		`CREATE UNIQUE INDEX IF NOT EXISTS idx_device_endpoint ON devices(endpoint_id)`,
		`INSERT OR IGNORE INTO push_groups(push_key) SELECT device_key FROM devices WHERE id NOT IN (SELECT device_id FROM device_groups)`,
		`INSERT OR IGNORE INTO device_groups(device_id,group_id) SELECT d.id,g.id FROM devices d JOIN push_groups g ON g.push_key=d.device_key`,
	} {
		if _, err := tx.Exec(q); err != nil {
			return err
		}
	}
	return tx.Commit()
}
func (s *SQLiteStorage) GetPushDevice(key string) (*model.Device, error) {
	var ownKey string
	err := s.db.QueryRow(`SELECT d.device_key FROM devices d JOIN device_groups m ON m.device_id=d.id JOIN push_groups g ON g.id=m.group_id WHERE g.push_key=? ORDER BY d.id LIMIT 1`, key).Scan(&ownKey)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return s.GetDeviceByKey(ownKey)
}

// platform is a category; endpoint_id identifies one exact phone or computer.
func (s *SQLiteStorage) Recipients(key, target string) ([]*model.Device, error) {
	if target == "" {
		target = "all"
	}
	if target == "desktop" {
		target = "pc"
	}
	rows, err := s.db.Query(`SELECT d.device_key FROM devices d JOIN device_groups m ON m.device_id=d.id JOIN push_groups g ON g.id=m.group_id WHERE g.push_key=? AND (?='all' OR d.platform=? OR d.endpoint_id=?) ORDER BY d.id`, key, target, target, target)
	if err != nil {
		return nil, err
	}
	var keys []string
	for rows.Next() {
		var k string
		if err = rows.Scan(&k); err != nil {
			rows.Close()
			return nil, err
		}
		keys = append(keys, k)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return nil, err
	}
	result := make([]*model.Device, 0, len(keys))
	for _, k := range keys {
		d, err := s.GetDeviceByKey(k)
		if err != nil {
			return nil, err
		}
		if d != nil {
			result = append(result, d)
		}
	}
	if len(result) == 0 {
		return nil, ErrNoTarget
	}
	return result, nil
}
func (s *SQLiteStorage) CreateBindingRequest(id string, candidate, confirming *model.Device, code string) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	now := time.Now().Unix()
	if _, err = tx.Exec(`DELETE FROM binding_requests WHERE expires_at<?`, now); err != nil {
		return err
	}
	var n int
	if err = tx.QueryRow(`SELECT COUNT(*) FROM binding_requests WHERE (pc_id=? OR mobile_id=?) AND created_at>?`, candidate.ID, confirming.ID, now-60).Scan(&n); err != nil {
		return err
	}
	if n > 0 {
		return ErrBindingRate
	}
	var ownGroup, targetGroup int64
	var pushKey string
	if tx.QueryRow(`SELECT m.group_id,g.push_key FROM device_groups m JOIN push_groups g ON g.id=m.group_id WHERE m.device_id=?`, candidate.ID).Scan(&ownGroup, &pushKey) != nil {
		return ErrBinding
	}
	if tx.QueryRow(`SELECT group_id FROM device_groups WHERE device_id=?`, confirming.ID).Scan(&targetGroup) != nil {
		return ErrBinding
	}
	if ownGroup == targetGroup || pushKey != candidate.DeviceKey {
		return ErrBinding
	}
	if tx.QueryRow(`SELECT COUNT(*) FROM device_groups WHERE group_id=?`, ownGroup).Scan(&n) != nil || n != 1 {
		return ErrBinding
	}
	hash := sha256.Sum256([]byte(id + ":" + code))
	if _, err = tx.Exec(`INSERT INTO binding_requests(id,pc_id,mobile_id,group_id,credential_hash,code_hash,expires_at,created_at) VALUES(?,?,?,?,?,?,?,?)`, id, candidate.ID, confirming.ID, targetGroup, revokedKeyHash(candidate.DeviceKey), hash[:], now+300, now); err != nil {
		return err
	}
	return tx.Commit()
}
func (s *SQLiteStorage) VerifyBinding(id, key, code, newKey string) (string, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return "", err
	}
	defer tx.Rollback()
	var candidateID, confirmingID, groupID, expires int64
	var attempts, consumed int
	var credential, expected []byte
	var result string
	err = tx.QueryRow(`SELECT pc_id,mobile_id,group_id,credential_hash,code_hash,expires_at,attempts,consumed,result_key FROM binding_requests WHERE id=?`, id).Scan(&candidateID, &confirmingID, &groupID, &credential, &expected, &expires, &attempts, &consumed, &result)
	if err == sql.ErrNoRows {
		return "", ErrBinding
	}
	if err != nil {
		return "", err
	}
	if expires <= time.Now().Unix() || attempts >= 5 || subtle.ConstantTimeCompare(credential, revokedKeyHash(key)) != 1 {
		return "", ErrBinding
	}
	hash := sha256.Sum256([]byte(id + ":" + code))
	if subtle.ConstantTimeCompare(expected, hash[:]) != 1 {
		if _, err = tx.Exec(`UPDATE binding_requests SET attempts=attempts+1 WHERE id=?`, id); err != nil {
			return "", err
		}
		if err = tx.Commit(); err != nil {
			return "", err
		}
		return "", ErrBinding
	}
	var pushKey string
	if tx.QueryRow(`SELECT push_key FROM push_groups WHERE id=?`, groupID).Scan(&pushKey) != nil {
		return "", ErrBinding
	}
	if consumed != 0 {
		var current string
		var currentGroup int64
		if result != newKey || tx.QueryRow(`SELECT d.device_key,m.group_id FROM devices d JOIN device_groups m ON m.device_id=d.id WHERE d.id=?`, candidateID).Scan(&current, &currentGroup) != nil || current != result || currentGroup != groupID {
			return "", ErrBinding
		}
		return pushKey, nil
	}
	var confirmingGroup int64
	if tx.QueryRow(`SELECT group_id FROM device_groups WHERE device_id=?`, confirmingID).Scan(&confirmingGroup) != nil || confirmingGroup != groupID {
		return "", ErrBinding
	}
	var current string
	var sourceGroup int64
	var n int
	if tx.QueryRow(`SELECT d.device_key,m.group_id FROM devices d JOIN device_groups m ON m.device_id=d.id WHERE d.id=?`, candidateID).Scan(&current, &sourceGroup) != nil || current != key {
		return "", ErrBinding
	}
	if tx.QueryRow(`SELECT COUNT(*) FROM device_groups WHERE group_id=?`, sourceGroup).Scan(&n) != nil || n != 1 {
		return "", ErrBinding
	}
	var exists bool
	if err = tx.QueryRow(`SELECT EXISTS(SELECT 1 FROM devices WHERE device_key=? UNION ALL SELECT 1 FROM push_groups WHERE push_key=? UNION ALL SELECT 1 FROM revoked_device_keys WHERE key_hash=?)`, newKey, newKey, revokedKeyHash(newKey)).Scan(&exists); err != nil {
		return "", err
	}
	if exists {
		return "", ErrBinding
	}
	if _, err = tx.Exec(`UPDATE device_groups SET group_id=? WHERE device_id=?`, groupID, candidateID); err != nil {
		return "", err
	}
	if _, err = tx.Exec(`UPDATE devices SET device_key=? WHERE id=?`, newKey, candidateID); err != nil {
		return "", err
	}
	if _, err = tx.Exec(`INSERT OR IGNORE INTO revoked_device_keys(key_hash) VALUES(?)`, revokedKeyHash(key)); err != nil {
		return "", err
	}
	if _, err = tx.Exec(`DELETE FROM messages WHERE device_id=?`, candidateID); err != nil {
		return "", err
	}
	if _, err = tx.Exec(`DELETE FROM push_groups WHERE id=?`, sourceGroup); err != nil {
		return "", err
	}
	if _, err = tx.Exec(`UPDATE binding_requests SET consumed=1,result_key=? WHERE id=?`, newKey, id); err != nil {
		return "", err
	}
	if err = tx.Commit(); err != nil {
		return "", err
	}
	return pushKey, nil
}

type BoundDevice struct {
	ID         int64  `json:"id"`
	Name       string `json:"name"`
	Platform   string `json:"platform"`
	EndpointID string `json:"endpoint_id"`
	IsSelf     bool   `json:"is_self"`
}

func (s *SQLiteStorage) BindingStatus(device *model.Device) (string, []BoundDevice, error) {
	var pushKey string
	var group int64
	if err := s.db.QueryRow(`SELECT g.push_key,g.id FROM push_groups g JOIN device_groups m ON m.group_id=g.id WHERE m.device_id=?`, device.ID).Scan(&pushKey, &group); err != nil {
		return "", nil, err
	}
	rows, err := s.db.Query(`SELECT d.id,COALESCE(d.name,''),d.platform,d.endpoint_id FROM devices d JOIN device_groups m ON m.device_id=d.id WHERE m.group_id=? ORDER BY d.id`, group)
	if err != nil {
		return "", nil, err
	}
	defer rows.Close()
	devices := make([]BoundDevice, 0)
	for rows.Next() {
		var d BoundDevice
		if err = rows.Scan(&d.ID, &d.Name, &d.Platform, &d.EndpointID); err != nil {
			return "", nil, err
		}
		d.IsSelf = d.ID == device.ID
		devices = append(devices, d)
	}
	return pushKey, devices, rows.Err()
}
func (s *SQLiteStorage) RemoveBinding(actor *model.Device, targetID int64) (string, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return "", err
	}
	defer tx.Rollback()
	var ownGroup, targetGroup int64
	var key string
	if tx.QueryRow(`SELECT group_id FROM device_groups WHERE device_id=?`, actor.ID).Scan(&ownGroup) != nil {
		return "", ErrBinding
	}
	if tx.QueryRow(`SELECT d.device_key,m.group_id FROM devices d JOIN device_groups m ON m.device_id=d.id WHERE d.id=?`, targetID).Scan(&key, &targetGroup) != nil || ownGroup != targetGroup {
		return "", ErrBinding
	}
	for _, q := range []string{`DELETE FROM messages WHERE device_id=?`, `DELETE FROM binding_requests WHERE pc_id=? OR mobile_id=?`, `DELETE FROM device_groups WHERE device_id=?`, `DELETE FROM devices WHERE id=?`} {
		args := []interface{}{targetID}
		if q == `DELETE FROM binding_requests WHERE pc_id=? OR mobile_id=?` {
			args = append(args, targetID)
		}
		if _, err = tx.Exec(q, args...); err != nil {
			return "", err
		}
	}
	if _, err = tx.Exec(`INSERT OR IGNORE INTO revoked_device_keys(key_hash) VALUES(?)`, revokedKeyHash(key)); err != nil {
		return "", err
	}
	if _, err = tx.Exec(`DELETE FROM push_groups WHERE id=? AND NOT EXISTS(SELECT 1 FROM device_groups WHERE group_id=?)`, ownGroup, ownGroup); err != nil {
		return "", err
	}
	if err = tx.Commit(); err != nil {
		return "", err
	}
	return key, nil
}
