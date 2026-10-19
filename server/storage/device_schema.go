package storage

import "database/sql"

// Upgrade device metadata without changing existing credentials or bindings.
func ensureDeviceColumns(tx *sql.Tx) error {
	rows, err := tx.Query(`PRAGMA table_info(devices)`)
	if err != nil {
		return err
	}
	columns := make(map[string]bool)
	for rows.Next() {
		var cid, notNull, primaryKey int
		var name, kind string
		var defaultValue interface{}
		if err := rows.Scan(&cid, &name, &kind, &notNull, &defaultValue, &primaryKey); err != nil {
			rows.Close()
			return err
		}
		columns[name] = true
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	for _, column := range []struct{ name, definition string }{
		{"platform", `TEXT NOT NULL DEFAULT 'mobile' CHECK(platform IN ('mobile','pc'))`},
		{"endpoint_id", `TEXT NOT NULL DEFAULT ''`},
	} {
		if !columns[column.name] {
			if _, err := tx.Exec(`ALTER TABLE devices ADD COLUMN ` + column.name + ` ` + column.definition); err != nil {
				return err
			}
		}
	}
	return nil
}

func backfillDeviceEndpoints(tx *sql.Tx) error {
	rows, err := tx.Query(`SELECT id,platform FROM devices WHERE endpoint_id=''`)
	if err != nil {
		return err
	}
	type device struct {
		id       int64
		platform string
	}
	var devices []device
	for rows.Next() {
		var d device
		if err := rows.Scan(&d.id, &d.platform); err != nil {
			rows.Close()
			return err
		}
		devices = append(devices, d)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	for _, d := range devices {
		id, err := availableEndpointID(tx, d.platform)
		if err != nil {
			return err
		}
		if _, err := tx.Exec(`UPDATE devices SET endpoint_id=? WHERE id=?`, id, d.id); err != nil {
			return err
		}
	}
	return nil
}
