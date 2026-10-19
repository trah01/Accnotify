package handler

import (
	"crypto"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"encoding/pem"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/accnotify/server/model"
	"github.com/accnotify/server/storage"
	"github.com/gin-gonic/gin"
)

func TestEncryptedDelivery(t *testing.T) {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	store, err := storage.NewSQLiteStorage(filepath.Join(t.TempDir(), "messages.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	device := &model.Device{DeviceKey: "test-device", PublicKey: string(pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: der}))}
	if err := store.CreateDevice(device); err != nil {
		t.Fatal(err)
	}
	hub := NewHub(store)
	hub.clients[device.DeviceKey] = &Client{deviceKey: device.DeviceKey, deviceID: device.ID, send: make(chan []byte, 10)}
	push := NewPushHandler(store, hub)
	webhook := NewWebhookHandler(store, hub)
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.POST("/push/:device_key", push.HandlePush)
	router.GET("/push/:device_key/:title/:body", push.HandleSimplePush)
	router.POST("/webhook/:device_key", webhook.HandleGenericWebhook)
	for _, tc := range []struct{ method, path, body string }{
		{"POST", "/push/test-device", `{"title":"secret-title","body":"secret-body","group":"secret-group","url":"https://example.com/private","image":"secret-image","icon":"secret-icon","sound":"secret-sound","badge":7}`},
		{"GET", "/push/test-device/secret-title/secret-body?group=secret-group&image=secret-image", ""},
		{"POST", "/webhook/test-device", `{"title":"secret-title","body":"secret-body","image":"secret-image"}`},
	} {
		t.Run(tc.method+tc.path, func(t *testing.T) {
			req := httptest.NewRequest(tc.method, tc.path, strings.NewReader(tc.body))
			req.Header.Set("Content-Type", "application/json")
			response := httptest.NewRecorder()
			router.ServeHTTP(response, req)
			if response.Code != http.StatusOK {
				t.Fatalf("push failed: %s", response.Body.String())
			}
			var result model.PushResponse
			if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil {
				t.Fatal(err)
			}
			messages, err := store.GetUndeliveredMessages(device.ID)
			if err != nil {
				t.Fatal(err)
			}
			var msg *model.Message
			for _, candidate := range messages {
				if candidate.MessageID == result.MessageID {
					msg = candidate
				}
			}
			if msg == nil {
				t.Fatal("missing queued message")
			}
			// Online and offline delivery share the same wire representation.
			wire, err := json.Marshal(encryptedWSMessage(msg))
			if err != nil {
				t.Fatal(err)
			}
			if strings.Contains(string(wire), "secret-") {
				t.Fatal("plaintext leaked onto the wire")
			}
			data := encryptedWSMessage(msg).Data.(map[string]interface{})
			if len(data) != 1 || data["encrypted_content"] == "" {
				t.Fatal("wire must contain only ciphertext")
			}
			encrypted, err := base64.StdEncoding.DecodeString(string(msg.EncryptedPayload))
			if err != nil {
				t.Fatal(err)
			}
			keyLen := int(binary.BigEndian.Uint16(encrypted[:2]))
			aesKey, err := key.Decrypt(rand.Reader, encrypted[2:2+keyLen], &rsa.OAEPOptions{Hash: crypto.SHA256, MGFHash: crypto.SHA1})
			if err != nil {
				t.Fatal(err)
			}
			block, err := aes.NewCipher(aesKey)
			if err != nil {
				t.Fatal(err)
			}
			gcm, err := cipher.NewGCM(block)
			if err != nil {
				t.Fatal(err)
			}
			payload, err := gcm.Open(nil, encrypted[2+keyLen:2+keyLen+12], encrypted[2+keyLen+12:], nil)
			if err != nil {
				t.Fatal(err)
			}
			var fields map[string]interface{}
			if err := json.Unmarshal(payload, &fields); err != nil {
				t.Fatal(err)
			}
			if !strings.Contains(fields["body"].(string), "secret-body") || fields["image"] != "secret-image" || fields["group"] != msg.Group {
				t.Fatal("notification fields were not preserved")
			}
			if tc.method == "POST" && strings.HasPrefix(tc.path, "/push/") {
				if fields["url"] != msg.URL || fields["icon"] != msg.Icon || fields["sound"] != msg.Sound || fields["badge"] != float64(msg.Badge) {
					t.Fatal("notification metadata was not preserved")
				}
			}
			wrongKey, err := rsa.GenerateKey(rand.Reader, 2048)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := wrongKey.Decrypt(rand.Reader, encrypted[2:2+keyLen], &rsa.OAEPOptions{Hash: crypto.SHA256, MGFHash: crypto.SHA1}); err == nil {
				t.Fatal("unrelated device decrypted the message")
			}
		})
	}
	t.Run("legacy queued ciphertext", func(t *testing.T) {
		legacy := &model.Message{DeviceID: device.ID, MessageID: "legacy", Title: "secret-title", Body: "secret-body", Image: "secret-image", EncryptedPayload: []byte("incompatible legacy ciphertext")}
		if err := store.CreateMessage(legacy); err != nil {
			t.Fatal(err)
		}
		client := &Client{deviceKey: device.DeviceKey, deviceID: device.ID, send: make(chan []byte, 10)}
		hub.sendUndeliveredMessages(client)
		found := false
		for len(client.send) > 0 {
			wire := <-client.send
			if strings.Contains(string(wire), "secret-") {
				t.Fatal("offline delivery leaked plaintext")
			}
			var ws model.WSMessage
			if err := json.Unmarshal(wire, &ws); err != nil {
				t.Fatal(err)
			}
			if ws.ID == "legacy" {
				payload := decryptAdminTestMessage(t, ws.Data.(map[string]interface{})["encrypted_content"].(string), key)
				if payload["body"] != "secret-body" || payload["image"] != "secret-image" {
					t.Fatal("queued message fields changed during re-encryption")
				}
				found = true
			}
		}
		if !found {
			t.Fatal("legacy message was not encrypted and replayed")
		}
	})
	for _, publicKey := range []string{"", "invalid PEM"} {
		if err := store.UpdateDevicePublicKey(device.DeviceKey, publicKey); err != nil {
			t.Fatal(err)
		}
		for _, path := range []string{"/push/test-device", "/webhook/test-device"} {
			req := httptest.NewRequest("POST", path, strings.NewReader(`{"body":"secret-body"}`))
			req.Header.Set("Content-Type", "application/json")
			response := httptest.NewRecorder()
			router.ServeHTTP(response, req)
			if response.Code != http.StatusBadRequest {
				t.Fatal("invalid or missing key must reject delivery")
			}
		}
	}
}
