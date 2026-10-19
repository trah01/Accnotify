package handler

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
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

func TestPushOperationsPreserveRevisionsAndReplay(t *testing.T) {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "messages.db")
	store, err := storage.NewSQLiteStorage(path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if store != nil {
			store.Close()
		}
	}()
	device := &model.Device{DeviceKey: "operations-test", PublicKey: string(pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: der}))}
	if err := store.CreateDevice(device); err != nil {
		t.Fatal(err)
	}
	hub := NewHub(store)
	push := NewPushHandler(store, hub)
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.POST("/push/:device_key", push.HandlePush)
	router.GET("/push/:device_key/:title/:body", push.HandleSimplePush)
	request := func(method, path, contentType, body string, status int) model.PushResponse {
		t.Helper()
		req := httptest.NewRequest(method, path, strings.NewReader(body))
		req.Header.Set("Content-Type", contentType)
		response := httptest.NewRecorder()
		router.ServeHTTP(response, req)
		if response.Code != status {
			t.Fatalf("status = %d, want %d", response.Code, status)
		}
		var result model.PushResponse
		if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil {
			t.Fatal(err)
		}
		return result
	}
	first := request("POST", "/push/operations-test", "application/json", `{"id":"backup","body":"fallback","markdown":"**first**","group":"work"}`, http.StatusOK)
	second := request("POST", "/push/operations-test", "application/x-www-form-urlencoded", "id=backup&markdown=second&group=work", http.StatusOK)
	deleted := request("GET", "/push/operations-test/title/body?id=backup&delete=1", "", "", http.StatusOK)
	request("POST", "/push/operations-test", "application/json", `{"delete":"1"}`, http.StatusBadRequest)
	request("POST", "/push/operations-test", "application/json", `{"id":42,"delete":"1"}`, http.StatusBadRequest)
	if first.MessageID == second.MessageID || second.MessageID == deleted.MessageID {
		t.Fatal("revisions must have distinct ACK IDs")
	}
	queued, err := store.GetUndeliveredMessages(device.ID)
	if err != nil || len(queued) != 3 {
		t.Fatalf("pending revisions = %d: %v", len(queued), err)
	}
	if queued[0].Body != "**first**" || queued[1].Body != "second" {
		t.Fatal("markdown did not override body for JSON/form requests")
	}
	for _, msg := range queued {
		if msg.NotificationID != "backup" {
			t.Fatal("external notification ID was lost")
		}
	}
	if !queued[2].DeleteRequested || queued[0].ID >= queued[1].ID || queued[1].ID >= queued[2].ID {
		t.Fatal("delete operation or delivery order was lost")
	}
	// An old ACK must not acknowledge a replacement or deletion command.
	if err := store.MarkMessageDelivered(device.ID, first.MessageID); err != nil {
		t.Fatal(err)
	}
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}
	store, err = storage.NewSQLiteStorage(path)
	if err != nil {
		t.Fatal(err)
	}
	hub = NewHub(store)
	client := &Client{deviceKey: device.DeviceKey, deviceID: device.ID, send: make(chan []byte, 10)}
	hub.sendUndeliveredMessages(client)
	if len(client.send) != 2 {
		t.Fatal("old ACK removed a newer revision or replay lost commands")
	}
	for index := 0; index < 2; index++ {
		var wire model.WSMessage
		if err := json.Unmarshal(<-client.send, &wire); err != nil {
			t.Fatal(err)
		}
		if wire.Sequence != queued[index+1].ID {
			t.Fatal("replay changed the delivery sequence")
		}
		payload := decryptAdminTestMessage(t, wire.Data.(map[string]interface{})["encrypted_content"].(string), key)
		if payload["id"] != "backup" || (index == 1 && payload["delete"] != "1") {
			t.Fatal("operation metadata was lost during offline re-encryption")
		}
	}
}
