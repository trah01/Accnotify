package storage

import (
	"errors"
	"path/filepath"
	"testing"

	"github.com/accnotify/server/model"
)

func TestManageDeviceLifecycle(t *testing.T) {
	path := filepath.Join(t.TempDir(), "devices.db")
	s, err := NewSQLiteStorage(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	a := &model.Device{DeviceKey: "old-a", PublicKey: "old-public"}
	b := &model.Device{DeviceKey: "other-b", PublicKey: "old-public"}
	for _, d := range []*model.Device{a, b} {
		if err := s.CreateDevice(d); err != nil {
			t.Fatal(err)
		}
		if err := s.CreateMessage(&model.Message{DeviceID: d.ID, MessageID: d.DeviceKey, Body: "retained"}); err != nil {
			t.Fatal(err)
		}
	}
	if err := s.ManageDevice(a.DeviceKey, "wrong-public", "reset_rsa", "", "new-public"); !errors.Is(err, ErrDeviceConflict) {
		t.Fatalf("stale identity accepted: %v", err)
	}
	if err := s.ManageDevice(a.DeviceKey, "old-public", "reset_rsa", "", "new-public"); err != nil {
		t.Fatal(err)
	}
	if err := s.ManageDevice(a.DeviceKey, "old-public", "reset_rsa", "", "new-public"); err != nil {
		t.Fatalf("RSA retry failed: %v", err)
	}
	changed, err := s.GetDeviceByKey(a.DeviceKey)
	if err != nil || changed.ID != a.ID || changed.PublicKey != "new-public" {
		t.Fatalf("RSA reset changed ID: %+v %v", changed, err)
	}
	if err := s.ManageDevice(a.DeviceKey, "new-public", "reset_all", b.DeviceKey, "collision-public"); err == nil {
		t.Fatal("duplicate ID accepted")
	}
	changed, _ = s.GetDeviceByKey(a.DeviceKey)
	if changed.PublicKey != "new-public" {
		t.Fatal("failed transaction changed identity")
	}
	if err := s.ManageDevice(a.DeviceKey, "new-public", "reset_all", "new-a", "next-public"); err != nil {
		t.Fatal(err)
	}
	if err := s.ManageDevice(a.DeviceKey, "new-public", "reset_all", "new-a", "next-public"); err != nil {
		t.Fatalf("full reset retry failed: %v", err)
	}
	old, err := s.GetDeviceByKey(a.DeviceKey)
	if err != nil || old != nil {
		t.Fatal("old ID still valid")
	}
	if err := s.CreateDevice(&model.Device{DeviceKey: a.DeviceKey}); !errors.Is(err, ErrRevokedDeviceKey) {
		t.Fatalf("revoked ID could register: %v", err)
	}
	pending, err := s.GetUndeliveredMessages(a.ID)
	if err != nil || len(pending) != 1 || pending[0].Body != "retained" {
		t.Fatal("reset lost pending messages")
	}
	if err := s.ManageDevice("new-a", "next-public", "unregister", "", ""); err != nil {
		t.Fatal(err)
	}
	if err := s.ManageDevice("new-a", "next-public", "unregister", "", ""); err != nil {
		t.Fatalf("unregister retry failed: %v", err)
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	s, err = NewSQLiteStorage(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err := s.CreateDevice(&model.Device{DeviceKey: "new-a"}); !errors.Is(err, ErrRevokedDeviceKey) {
		t.Fatal("revocation lost after restart")
	}
	pending, err = s.GetUndeliveredMessages(a.ID)
	if err != nil || len(pending) != 0 {
		t.Fatal("unregistered device retained server messages")
	}
	other, err := s.GetDeviceByKey(b.DeviceKey)
	if err != nil || other.PublicKey != "old-public" {
		t.Fatal("other device was changed")
	}
	pending, err = s.GetUndeliveredMessages(b.ID)
	if err != nil || len(pending) != 1 {
		t.Fatal("other device messages were removed")
	}
}
