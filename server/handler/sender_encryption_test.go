package handler

import (
	"crypto/aes"
	"crypto/cipher"
	"encoding/base64"
	"encoding/json"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/accnotify/server/model"
	"github.com/accnotify/server/storage"
	"github.com/gin-gonic/gin"
)

func TestSenderEncryptedDelivery(t *testing.T) {
	path := filepath.Join(t.TempDir(), "messages.db")
	store, err := storage.NewSQLiteStorage(path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { store.Close() }()
	device := &model.Device{DeviceKey: "sender-test-device"} // No RSA key is needed for sender ciphertext.
	if err := store.CreateDevice(device); err != nil {
		t.Fatal(err)
	}
	hub := NewHub(store)
	client := &Client{deviceKey: device.DeviceKey, deviceID: device.ID, send: make(chan []byte, 10)}
	hub.clients[device.DeviceKey] = client
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.POST("/push/:device_key", NewPushHandler(store, hub).HandlePush)
	// Deterministic test-only key and nonce, never used for actual sending.
	key := make([]byte, 32)
	nonce := make([]byte, 12)
	block, _ := aes.NewCipher(key)
	gcm, _ := cipher.NewGCM(block)
	plaintext := `{"title":"private-title","body":"private-body","group":"private-group"}`
	envelope := senderEnvelope{Encryption: "aes-256-gcm", Nonce: base64.StdEncoding.EncodeToString(nonce),
		Ciphertext: base64.StdEncoding.EncodeToString(gcm.Seal(nil, nonce, []byte(plaintext), nil))}
	encoded, _ := json.Marshal(envelope)
	request := func(body string) int {
		req := httptest.NewRequest("POST", "/push/"+device.DeviceKey, strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		response := httptest.NewRecorder()
		router.ServeHTTP(response, req)
		return response.Code
	}
	if code := request(string(encoded)); code != 200 {
		t.Fatalf("encrypted push returned %d", code)
	}
	verifyWire := func(wire []byte) {
		t.Helper()
		if strings.Contains(string(wire), "private-") {
			t.Fatal("plaintext leaked on wire")
		}
		var message struct {
			Data senderEnvelope `json:"data"`
		}
		if err := json.Unmarshal(wire, &message); err != nil || message.Data != envelope {
			t.Fatal("sender ciphertext changed")
		}
		encrypted, _ := base64.StdEncoding.DecodeString(message.Data.Ciphertext)
		decoded, err := gcm.Open(nil, nonce, encrypted, nil)
		if err != nil || string(decoded) != plaintext {
			t.Fatal("recipient could not decrypt")
		}
	}
	verifyWire(<-client.send)
	queued, err := store.GetUndeliveredMessages(device.ID)
	if err != nil || len(queued) != 1 {
		t.Fatal("missing encrypted queued message")
	}
	msg := queued[0]
	if msg.Title != "" || msg.Body != "" || msg.Group != "" || strings.Contains(string(msg.EncryptedPayload), "private-") {
		t.Fatal("plaintext stored in server message")
	}
	for _, tc := range []struct{ name, body string }{
		{"mixed plaintext", strings.TrimSuffix(string(encoded), "}") + `,"body":"private-body"}`},
		{"unsupported algorithm", strings.Replace(string(encoded), "aes-256-gcm", "aes-cbc", 1)},
		{"wrong nonce length", `{"encryption":"aes-256-gcm","nonce":"AA==","ciphertext":"` + envelope.Ciphertext + `"}`},
		{"invalid base64", `{"encryption":"aes-256-gcm","nonce":"` + envelope.Nonce + `","ciphertext":"invalid!"}`},
		{"missing tag", `{"encryption":"aes-256-gcm","nonce":"` + envelope.Nonce + `","ciphertext":"AA=="}`},
		{"incomplete envelope", `{"ciphertext":"` + envelope.Ciphertext + `"}`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if code := request(tc.body); code != 400 {
				t.Fatalf("malformed request returned %d", code)
			}
		})
	}
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}
	store, err = storage.NewSQLiteStorage(path)
	if err != nil {
		t.Fatal(err)
	}
	hub = NewHub(store)
	reconnected := &Client{deviceKey: device.DeviceKey, deviceID: device.ID, send: make(chan []byte, 10)}
	hub.sendUndeliveredMessages(reconnected)
	if len(reconnected.send) != 1 {
		t.Fatal("ciphertext was not replayed after restart")
	}
	verifyWire(<-reconnected.send)
	if err := store.MarkMessageDelivered(device.ID, msg.MessageID); err != nil {
		t.Fatal(err)
	}
	queued, err = store.GetUndeliveredMessages(device.ID)
	if err != nil || len(queued) != 0 {
		t.Fatal("acknowledged ciphertext was retained")
	}
}
