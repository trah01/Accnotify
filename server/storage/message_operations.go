package storage

// Existing installations retain all pending messages when operation metadata is added.
func (s *SQLiteStorage) ensureMessageOperationColumns() error {
	rows, err := s.db.Query(`PRAGMA table_info(messages)`)
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
		{"notification_id", "TEXT NOT NULL DEFAULT ''"},
		{"delete_requested", "BOOLEAN NOT NULL DEFAULT FALSE"},
	} {
		if !columns[column.name] {
			if _, err := s.db.Exec("ALTER TABLE messages ADD COLUMN " + column.name + " " + column.definition); err != nil {
				return err
			}
		}
	}
	return nil
}
