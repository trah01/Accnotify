package storage

import (
	"errors"
	"github.com/accnotify/server/model"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestShortEndpointIDsAndBindingLimits(t *testing.T) {
	s, err := NewSQLiteStorage(filepath.Join(t.TempDir(), "binding.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	mobile := &model.Device{DeviceKey: strings.Repeat("M", 32), Platform: "mobile"}
	if err = s.CreateDevice(mobile); err != nil {
		t.Fatal(err)
	}
	pc := &model.Device{DeviceKey: strings.Repeat("P", 32), Platform: "pc"}
	if err = s.CreateDevice(pc); err != nil {
		t.Fatal(err)
	}
	for _, device := range []*model.Device{mobile, pc} {
		prefix := device.Platform + "-"
		suffix := strings.TrimPrefix(device.EndpointID, prefix)
		if !strings.HasPrefix(device.EndpointID, prefix) || len(suffix) != 6 || strings.Trim(suffix, "0123456789abcdefghijklmnopqrstuvwxyz") != "" {
			t.Fatalf("unexpected endpoint format: %q", device.EndpointID)
		}
	}
	if err = s.CreateBindingRequest("first", pc, mobile, "123456"); err != nil {
		t.Fatal(err)
	}
	if err = s.CreateBindingRequest("too-soon", pc, mobile, "123456"); !errors.Is(err, ErrBindingRate) {
		t.Fatal("binding send cooldown missing")
	}
	for i := 0; i < 5; i++ {
		if _, err = s.VerifyBinding("first", pc.DeviceKey, "000000", strings.Repeat("N", 32)); !errors.Is(err, ErrBinding) {
			t.Fatal(err)
		}
	}
	if _, err = s.VerifyBinding("first", pc.DeviceKey, "123456", strings.Repeat("N", 32)); !errors.Is(err, ErrBinding) {
		t.Fatal("sixth attempt accepted")
	}
	s.db.Exec(`UPDATE binding_requests SET created_at=?,expires_at=? WHERE id='first'`, time.Now().Unix()-61, time.Now().Unix()-1)
	if err = s.CreateBindingRequest("expired", pc, mobile, "123456"); err != nil {
		t.Fatal(err)
	}
	s.db.Exec(`UPDATE binding_requests SET expires_at=? WHERE id='expired'`, time.Now().Unix()-1)
	if _, err = s.VerifyBinding("expired", pc.DeviceKey, "123456", strings.Repeat("N", 32)); !errors.Is(err, ErrBinding) {
		t.Fatal("expired code accepted")
	}
}

func TestMultipleDevicesIndependentRemoval(t *testing.T) {
	s, err := NewSQLiteStorage(filepath.Join(t.TempDir(), "binding.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	mobile := &model.Device{DeviceKey: strings.Repeat("M", 32), PublicKey: "original"}
	s.CreateDevice(mobile)
	for _, letter := range []string{"A", "B", "C", "D"} {
		platform := "pc"
		if letter == "C" || letter == "D" {
			platform = "mobile"
		}
		pc := &model.Device{DeviceKey: strings.Repeat(letter, 32), Platform: platform}
		if err = s.CreateDevice(pc); err != nil {
			t.Fatal(err)
		}
		s.db.Exec(`UPDATE binding_requests SET created_at=?`, time.Now().Unix()-61)
		if err = s.CreateBindingRequest(letter, pc, mobile, "123456"); err != nil {
			t.Fatal(err)
		}
		if _, err = s.VerifyBinding(letter, pc.DeviceKey, "123456", strings.Repeat(strings.ToLower(letter), 32)); err != nil {
			t.Fatal(err)
		}
	}
	all, err := s.Recipients(mobile.DeviceKey, "all")
	if err != nil || len(all) != 5 {
		t.Fatal("multiple PC routing failed", err)
	}
	pcs, err := s.Recipients(mobile.DeviceKey, "pc")
	if err != nil || len(pcs) != 2 {
		t.Fatal("PC routing failed", err)
	}
	if err = s.ManageDevice(mobile.DeviceKey, "original", "unregister", "", ""); err != nil {
		t.Fatal(err)
	}
	remaining, err := s.Recipients(mobile.DeviceKey, "all")
	if err != nil || len(remaining) != 4 {
		t.Fatal("removing initial phone broke shared ID", err)
	}
	seen := map[string]bool{}
	for _, d := range remaining {
		if d.EndpointID == "" || seen[d.EndpointID] {
			t.Fatal("endpoint identifiers are not unique")
		}
		seen[d.EndpointID] = true
		exact, err := s.Recipients(mobile.DeviceKey, d.EndpointID)
		if err != nil || len(exact) != 1 || exact[0].ID != d.ID {
			t.Fatal("exact device target failed", err)
		}
	}
	if current, _ := s.GetDeviceByKey(mobile.DeviceKey); current != nil {
		t.Fatal("removed phone credential remains valid")
	}
	if _, err := s.Recipients(mobile.DeviceKey, "mobile"); err != nil {
		t.Fatal("other phones disappeared", err)
	}
	chosen := remaining[0]
	newKey := strings.Repeat("Z", 32)
	if err = s.ManageDevice(chosen.DeviceKey, chosen.PublicKey, "reset_all", newKey, "newrsa"); err != nil {
		t.Fatal(err)
	}
	if shared, err := s.Recipients(mobile.DeviceKey, "all"); err != nil || len(shared) != 3 {
		t.Fatal("reset affected other members", err)
	}
	standalone, err := s.Recipients(newKey, "all")
	if err != nil || len(standalone) != 1 || standalone[0].EndpointID != chosen.EndpointID {
		t.Fatal("reset lost endpoint identifier", err)
	}
}
