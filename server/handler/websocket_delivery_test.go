package handler

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/pem"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/accnotify/server/model"
	"github.com/accnotify/server/storage"
	"github.com/gorilla/websocket"
)

func TestWebSocketAckScopesDelivery(t *testing.T) {
	store, err := storage.NewSQLiteStorage(filepath.Join(t.TempDir(), "ack.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	for _, id := range []string{"own", "other"} {
		device := &model.Device{DeviceKey: id}
		if err := store.CreateDevice(device); err != nil {
			t.Fatal(err)
		}
		if err := store.CreateMessage(&model.Message{DeviceID: device.ID, MessageID: id}); err != nil {
			t.Fatal(err)
		}
	}
	hub := NewHub(store)
	hub.unregister = make(chan *Client, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		client := &Client{hub: hub, conn: conn, deviceID: 1}
		client.readPump()
	}))
	defer server.Close()
	conn, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(server.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		conn.Close()
		select {
		case <-hub.unregister:
		case <-time.After(time.Second):
			t.Error("read pump did not exit")
		}
	}()
	for _, id := range []string{"other", "own"} {
		if err := conn.WriteJSON(model.WSMessage{Type: model.WSTypeAck, ID: id}); err != nil {
			t.Fatal(err)
		}
	}
	deadline := time.Now().Add(time.Second)
	for {
		pending, err := store.GetUndeliveredMessages(1)
		if err != nil {
			t.Fatal(err)
		}
		if len(pending) == 0 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("own ACK was not processed")
		}
		time.Sleep(time.Millisecond)
	}
	pending, err := store.GetUndeliveredMessages(2)
	if err != nil || len(pending) != 1 {
		t.Fatalf("ACK confirmed another device's message: %d %v", len(pending), err)
	}
}

func TestReplayDisconnectAndLargeBacklog(t *testing.T) {
	store, err := storage.NewSQLiteStorage(filepath.Join(t.TempDir(), "replay.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	device := &model.Device{DeviceKey: "replay", PublicKey: string(pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: der}))}
	if err := store.CreateDevice(device); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 300; i++ {
		msg := &model.Message{DeviceID: device.ID, MessageID: time.Unix(int64(i), 0).String(), EncryptedPayload: []byte("existing ciphertext")}
		if err := store.CreateMessage(msg); err != nil {
			t.Fatal(err)
		}
	}
	hub := NewHub(store)
	client := &Client{deviceKey: device.DeviceKey, deviceID: device.ID, send: make(chan []byte, 1)}
	finished := make(chan struct{})
	go func() { hub.sendUndeliveredMessages(client); close(finished) }()
	for i := 0; i < 300; i++ {
		select {
		case <-client.send:
		case <-time.After(2 * time.Second):
			t.Fatalf("replay stopped at %d", i)
		}
	}
	<-finished
	// Concurrent replay, live sends, and repeated disconnects must not panic.
	client = &Client{deviceKey: device.DeviceKey, deviceID: device.ID, send: make(chan []byte, 1)}
	var wg sync.WaitGroup
	wg.Add(3)
	go func() { defer wg.Done(); hub.sendUndeliveredMessages(client) }()
	go func() {
		defer wg.Done()
		for i := 0; i < 1000; i++ {
			client.enqueue([]byte("live"), false)
		}
	}()
	go func() {
		defer wg.Done()
		for i := 0; i < 100; i++ {
			client.disconnect()
		}
	}()
	wg.Wait()
	if client.enqueue([]byte("closed"), true) {
		t.Fatal("closed connection accepted a message")
	}
	pending, err := store.GetUndeliveredMessages(device.ID)
	if err != nil || len(pending) != 300 {
		t.Fatalf("replay marked messages without ACK: %d %v", len(pending), err)
	}
}
