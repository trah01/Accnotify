package handler

import (
	"encoding/json"
	"log"
	"sync"
	"time"

	"github.com/accnotify/server/model"
	"github.com/accnotify/server/storage"
	"github.com/gorilla/websocket"
)

const (
	writeWait      = 10 * time.Second
	pongWait       = 60 * time.Second
	pingPeriod     = 30 * time.Second
	maxMessageSize = 512 * 1024 // 512KB
)

// Client represents a WebSocket client connection
type Client struct {
	hub       *Hub
	conn      *websocket.Conn
	send      chan []byte
	deviceKey string
	deviceID  int64
	done      chan struct{}
	initDone  sync.Once
	stopOnce  sync.Once
}

// The send queue stays open; disconnect closes only done so replay cannot send
// into a closed channel. Unacknowledged messages remain in storage.
func (c *Client) doneChannel() <-chan struct{} {
	c.initDone.Do(func() { c.done = make(chan struct{}) })
	return c.done
}

func (c *Client) disconnect() {
	c.doneChannel()
	c.stopOnce.Do(func() {
		close(c.done)
		if c.conn != nil {
			c.conn.Close()
		}
	})
}

func (c *Client) enqueue(data []byte, wait bool) bool {
	done := c.doneChannel()
	select {
	case <-done:
		return false
	default:
	}
	if !wait {
		select {
		case <-done:
			return false
		case c.send <- data:
			return true
		default:
			return false
		}
	}
	timer := time.NewTimer(writeWait)
	defer timer.Stop()
	select {
	case <-done:
		return false
	case c.send <- data:
		return true
	case <-timer.C:
		return false
	}
}

// Hub manages all WebSocket connections
type Hub struct {
	clients    map[string]*Client // deviceKey -> Client
	register   chan *Client
	unregister chan *Client
	storage    *storage.SQLiteStorage
	mu         sync.RWMutex
}

// NewHub creates a new WebSocket hub
func NewHub(storage *storage.SQLiteStorage) *Hub {
	return &Hub{
		clients:    make(map[string]*Client),
		register:   make(chan *Client),
		unregister: make(chan *Client),
		storage:    storage,
	}
}

// Run starts the hub's main loop
func (h *Hub) Run() {
	for {
		select {
		case client := <-h.register:
			h.mu.Lock()
			device, err := h.storage.GetDeviceByKey(client.deviceKey)
			if err != nil || device == nil || device.ID != client.deviceID {
				client.disconnect()
				h.mu.Unlock()
				continue
			}
			// Close existing connection if any
			if existing, ok := h.clients[client.deviceKey]; ok {
				existing.disconnect()
			}
			h.clients[client.deviceKey] = client
			h.mu.Unlock()
			log.Printf("Client registered: device_id=%d", client.deviceID)

			// Send undelivered messages
			go h.sendUndeliveredMessages(client)

		case client := <-h.unregister:
			h.mu.Lock()
			if existing, ok := h.clients[client.deviceKey]; ok && existing == client {
				delete(h.clients, client.deviceKey)
				client.disconnect()
			}
			h.mu.Unlock()
			log.Printf("Client unregistered: device_id=%d", client.deviceID)

		}
	}
}

func (h *Hub) DisconnectDevice(deviceKey string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if client := h.clients[deviceKey]; client != nil {
		delete(h.clients, deviceKey)
		client.disconnect()
	}
}

// sendUndeliveredMessages sends all undelivered messages to a newly connected client
func (h *Hub) sendUndeliveredMessages(client *Client) {
	messages, err := h.storage.GetUndeliveredMessages(client.deviceID)
	if err != nil {
		log.Printf("Error getting undelivered messages: %v", err)
		return
	}

	if len(messages) == 0 {
		return
	}
	device, err := h.storage.GetDeviceByKey(client.deviceKey)
	if err != nil || device == nil {
		return
	}
	for _, msg := range messages {
		if time.Since(msg.CreatedAt) >= storage.MessageRetention {
			continue
		}
		// Re-encrypt stored fields with the current Android-compatible parameters
		// and registered key, including ciphertext queued by earlier server versions.
		select {
		case <-client.doneChannel():
			return
		default:
		}
		if err := encryptDeviceMessage(device, msg); err != nil {
			log.Printf("Unable to encrypt queued message %s", msg.MessageID)
			return
		}
		wsMsg := encryptedWSMessage(msg)

		data, err := json.Marshal(wsMsg)
		if err != nil {
			continue
		}

		if !client.enqueue(data, true) {
			client.disconnect()
			return
		}
	}
}

// SendToDevice sends a message to a specific device
func (h *Hub) SendToDevice(deviceKey string, msg *model.WSMessage) bool {
	data, err := json.Marshal(msg)
	if err != nil {
		return false
	}

	h.mu.RLock()
	client := h.clients[deviceKey]
	h.mu.RUnlock()
	if client == nil {
		return false
	}
	if !client.enqueue(data, false) {
		client.disconnect()
		return false
	}
	return true
}

// IsOnline checks if a device is currently connected
func (h *Hub) OnlineCount() int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.clients)
}

func (h *Hub) IsOnline(deviceKey string) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	_, ok := h.clients[deviceKey]
	return ok
}

// readPump pumps messages from the WebSocket connection to the hub
func (c *Client) readPump() {
	defer func() {
		c.hub.unregister <- c
		c.disconnect()
	}()

	c.conn.SetReadLimit(maxMessageSize)
	c.conn.SetReadDeadline(time.Now().Add(pongWait))
	c.conn.SetPongHandler(func(string) error {
		c.conn.SetReadDeadline(time.Now().Add(pongWait))
		return nil
	})

	for {
		_, message, err := c.conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseNormalClosure, websocket.CloseGoingAway, websocket.CloseAbnormalClosure) {
				log.Printf("WebSocket error: %v", err)
			}
			break
		}

		// Handle incoming messages (ACKs, etc.)
		var wsMsg model.WSMessage
		if err := json.Unmarshal(message, &wsMsg); err != nil {
			continue
		}

		switch wsMsg.Type {
		case model.WSTypeAck:
			// Delete the confirmed message, scoped to the receiving device.
			if wsMsg.ID != "" {
				if err := c.hub.storage.MarkMessageDelivered(c.deviceID, wsMsg.ID); err != nil {
					log.Printf("Unable to acknowledge message: %v", err)
				}
			}
		case model.WSTypePong:
			c.conn.SetReadDeadline(time.Now().Add(pongWait))
		}
	}
}

// writePump pumps messages from the hub to the WebSocket connection
func (c *Client) writePump() {
	ticker := time.NewTicker(pingPeriod)
	defer func() {
		ticker.Stop()
		c.disconnect()
	}()

	for {
		select {
		case <-c.doneChannel():
			return
		case message := <-c.send:
			var queued model.WSMessage
			if json.Unmarshal(message, &queued) == nil && queued.Type == model.WSTypeMessage && queued.Timestamp > 0 && time.Since(time.Unix(queued.Timestamp, 0)) >= storage.MessageRetention {
				continue
			}
			c.conn.SetWriteDeadline(time.Now().Add(writeWait))

			w, err := c.conn.NextWriter(websocket.TextMessage)
			if err != nil {
				return
			}
			w.Write(message)

			if err := w.Close(); err != nil {
				return
			}

		case <-ticker.C:
			c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			pingMsg := model.WSMessage{
				Type:      model.WSTypePing,
				Timestamp: time.Now().Unix(),
			}
			data, _ := json.Marshal(pingMsg)
			if err := c.conn.WriteMessage(websocket.TextMessage, data); err != nil {
				return
			}
		}
	}
}
