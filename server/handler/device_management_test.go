package handler

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/accnotify/server/model"
	"github.com/accnotify/server/storage"
	"github.com/gin-gonic/gin"
)

func TestDeviceManagementAuthorization(t *testing.T) {
	s, err := storage.NewSQLiteStorage(filepath.Join(t.TempDir(), "device.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	device := &model.Device{DeviceKey: "current-secret", PublicKey: "previous-public"}
	if err := s.CreateDevice(device); err != nil {
		t.Fatal(err)
	}
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	pub := string(pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: der}))
	hub := NewHub(s)
	client := &Client{deviceID: device.ID, deviceKey: device.DeviceKey, send: make(chan []byte, 1)}
	hub.clients[device.DeviceKey] = client
	h := NewPushHandler(s, hub)
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.POST("/device/manage", h.HandleManageDevice)
	router.POST("/register", h.HandleRegister)
	call := func(path, token string, values map[string]string) int {
		body, _ := json.Marshal(values)
		r := httptest.NewRequest("POST", path, strings.NewReader(string(body)))
		r.Header.Set("Content-Type", "application/json")
		if token != "" {
			r.Header.Set("Authorization", "Bearer "+token)
		}
		w := httptest.NewRecorder()
		router.ServeHTTP(w, r)
		return w.Code
	}
	values := map[string]string{"action": "reset_rsa", "device_key": device.DeviceKey, "expected_public_key": device.PublicKey, "public_key": pub}
	for _, token := range []string{"", "other-secret"} {
		if code := call("/device/manage", token, values); code != 401 {
			t.Fatalf("unauthorized reset: %d", code)
		}
	}
	values["public_key"] = "invalid"
	if code := call("/device/manage", device.DeviceKey, values); code != 400 {
		t.Fatalf("invalid public key accepted: %d", code)
	}
	values["public_key"] = pub
	if code := call("/device/manage", device.DeviceKey, values); code != 200 {
		t.Fatalf("reset failed: %d", code)
	}
	select {
	case <-client.doneChannel():
	default:
		t.Fatal("old connection retained")
	}
	values = map[string]string{"action": "unregister", "device_key": device.DeviceKey, "expected_public_key": pub}
	if code := call("/device/manage", device.DeviceKey, values); code != 200 {
		t.Fatalf("unregister failed: %d", code)
	}
	if code := call("/register", "", map[string]string{"device_key": device.DeviceKey, "public_key": pub}); code != 409 {
		t.Fatalf("revoked ID re-registered: %d", code)
	}
}
