package storage

import (
	"bytes"
	"database/sql"
	"path/filepath"
	"strings"
	"testing"
)

func legacyDeviceDatabase(t *testing.T, path string, partial bool) *sql.DB {
	t.Helper()
	db, err := sql.Open("sqlite3", path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	queries := []string{
		`CREATE TABLE devices(id INTEGER PRIMARY KEY AUTOINCREMENT,device_key TEXT UNIQUE NOT NULL,public_key TEXT,name TEXT,created_at DATETIME DEFAULT CURRENT_TIMESTAMP,last_seen DATETIME DEFAULT CURRENT_TIMESTAMP)`,
		`CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,device_id INTEGER NOT NULL,message_id TEXT UNIQUE NOT NULL,title TEXT,body TEXT,group_name TEXT,icon TEXT,url TEXT,sound TEXT,badge INTEGER DEFAULT 0,encrypted_payload BLOB,created_at DATETIME DEFAULT CURRENT_TIMESTAMP,delivered BOOLEAN DEFAULT FALSE)`,
		`INSERT INTO devices(device_key,public_key,name) VALUES('legacy-a','public-a','phone-a'),('legacy-b','public-b','phone-b')`,
		`INSERT INTO messages(device_id,message_id,encrypted_payload) VALUES(1,'pending',X'010203')`,
	}
	if partial {
		queries = append(queries,
			`ALTER TABLE devices ADD COLUMN platform TEXT NOT NULL DEFAULT 'mobile'`,
			`ALTER TABLE devices ADD COLUMN endpoint_id TEXT NOT NULL DEFAULT ''`,
			`UPDATE devices SET platform='pc' WHERE id=2`,
		)
	}
	for _, q := range queries {
		if _, err := db.Exec(q); err != nil {
			t.Fatal(err)
		}
	}
	return db
}

func TestUpgradeLegacyDeviceSchema(t *testing.T) {
	for _, partial := range []bool{false, true} {
		name := "missing-columns"
		if partial {
			name = "empty-endpoints"
		}
		t.Run(name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "legacy.db")
			legacyDeviceDatabase(t, path, partial)
			var endpoints []string
			for attempt := 0; attempt < 2; attempt++ {
				s, err := NewSQLiteStorage(path)
				if err != nil {
					t.Fatal(err)
				}
				t.Cleanup(func() { s.Close() })
				for i, key := range []string{"legacy-a", "legacy-b"} {
					d, err := s.GetDeviceByKey(key)
					platform := "mobile"
					if partial && i == 1 {
						platform = "pc"
					}
					if err != nil || d == nil || d.ID != int64(i+1) || d.Platform != platform || !strings.HasPrefix(d.EndpointID, platform+"-") {
						t.Fatalf("device metadata not preserved: %+v %v", d, err)
					}
					if d.PublicKey != "public-"+string(rune('a'+i)) || d.Name != "phone-"+string(rune('a'+i)) {
						t.Fatalf("device identity changed: %+v", d)
					}
					if attempt == 0 {
						endpoints = append(endpoints, d.EndpointID)
						recipients, err := s.Recipients(key, d.EndpointID)
						if err != nil || len(recipients) != 1 || recipients[0].ID != d.ID {
							t.Fatalf("old push key no longer routes: %v %v", recipients, err)
						}
					} else if endpoints[i] != d.EndpointID {
						t.Fatal("endpoint changed on restart")
					}
				}
				if endpoints[0] == endpoints[1] {
					t.Fatal("duplicate endpoints")
				}
				pending, err := s.GetUndeliveredMessages(1)
				if err != nil || len(pending) != 1 || !bytes.Equal(pending[0].EncryptedPayload, []byte{1, 2, 3}) {
					t.Fatalf("pending ciphertext changed: %+v %v", pending, err)
				}
				if attempt == 0 {
					// Existing shared groups must survive the next startup.
					if _, err := s.db.Exec(`UPDATE device_groups SET group_id=(SELECT group_id FROM device_groups WHERE device_id=1) WHERE device_id=2`); err != nil {
						t.Fatal(err)
					}
				} else {
					members, err := s.Recipients("legacy-a", "all")
					if err != nil || len(members) != 2 {
						t.Fatalf("binding changed on restart: %+v %v", members, err)
					}
				}
				if err := s.Close(); err != nil {
					t.Fatal(err)
				}
			}
		})
	}
}

func TestUpgradeLegacyDeviceSchemaRollback(t *testing.T) {
	path := filepath.Join(t.TempDir(), "legacy.db")
	db := legacyDeviceDatabase(t, path, false)
	if _, err := db.Exec(`CREATE TABLE push_groups(id INTEGER PRIMARY KEY,push_key TEXT UNIQUE NOT NULL);
		CREATE TRIGGER fail_group BEFORE INSERT ON push_groups BEGIN SELECT RAISE(ABORT,'migration failure'); END`); err != nil {
		t.Fatal(err)
	}
	if s, err := NewSQLiteStorage(path); err == nil {
		s.Close()
		t.Fatal("expected migration failure")
	}
	var count int
	if err := db.QueryRow(`SELECT COUNT(*) FROM pragma_table_info('devices') WHERE name IN ('platform','endpoint_id')`).Scan(&count); err != nil || count != 0 {
		t.Fatalf("device schema was partially committed: %d %v", count, err)
	}
	if err := db.QueryRow(`SELECT COUNT(*) FROM devices`).Scan(&count); err != nil || count != 2 {
		t.Fatalf("existing devices changed: %d %v", count, err)
	}
}
