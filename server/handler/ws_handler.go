package handler

import (
	"log"
	"net/http"

	"github.com/accnotify/server/storage"
	"github.com/gorilla/websocket"
)

var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	CheckOrigin: func(r *http.Request) bool {
		return true // Allow all origins for now
	},
}

// WSHandler handles WebSocket upgrade requests
type WSHandler struct {
	hub     *Hub
	storage *storage.SQLiteStorage
}

// NewWSHandler creates a new WebSocket handler
func NewWSHandler(hub *Hub, storage *storage.SQLiteStorage) *WSHandler {
	return &WSHandler{
		hub:     hub,
		storage: storage,
	}
}

// HandleConnect handles the WebSocket connection upgrade
func (h *WSHandler) HandleConnect(w http.ResponseWriter, r *http.Request) {
	deviceKey := r.URL.Query().Get("key")
	if deviceKey == "" {
		http.Error(w, "Missing device key", http.StatusBadRequest)
		return
	}

	// Verify device exists
	device, err := h.storage.GetDeviceByKey(deviceKey)
	if err != nil {
		http.Error(w, "Database error", http.StatusInternalServerError)
		return
	}
	if device == nil {
		http.Error(w, "Invalid device key", http.StatusUnauthorized)
		return
	}

	// Upgrade to WebSocket
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("WebSocket upgrade failed: %v", err)
		return
	}

	// Update last seen
	h.storage.UpdateDeviceLastSeen(deviceKey)

	// Create client
	client := &Client{
		hub:       h.hub,
		conn:      conn,
		send:      make(chan []byte, 256),
		deviceKey: deviceKey,
		deviceID:  device.ID,
	}

	// Register client
	h.hub.register <- client

	// Start pumps
	go client.writePump()
	go client.readPump()
}
