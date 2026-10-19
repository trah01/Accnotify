package handler

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/accnotify/server/model"
	"github.com/accnotify/server/storage"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

// PushHandler handles push notification requests
type PushHandler struct {
	storage *storage.SQLiteStorage
	hub     *Hub
}

// NewPushHandler creates a new push handler
func NewPushHandler(storage *storage.SQLiteStorage, hub *Hub) *PushHandler {
	return &PushHandler{
		storage: storage,
		hub:     hub,
	}
}

// HandlePush handles POST /push/:device_key
// Supports JSON body, form-encoded body, and query parameters (Bark-compatible)
func (h *PushHandler) HandlePush(c *gin.Context) {
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 8*1024*1024)
	deviceKey := c.Param("device_key")
	if deviceKey == "" {
		c.JSON(http.StatusBadRequest, model.PushResponse{
			Success: false,
			Error:   "Missing device key",
		})
		return
	}

	// Get device
	device, err := h.storage.GetPushDevice(deviceKey)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.PushResponse{
			Success: false,
			Error:   "Database error",
		})
		return
	}
	if device == nil {
		c.JSON(http.StatusNotFound, model.PushResponse{
			Success: false,
			Error:   "Device not found",
		})
		return
	}

	// Parse request - support JSON, form, and query params (Bark-compatible)
	var req model.PushRequest
	contentType := c.GetHeader("Content-Type")
	if strings.Contains(contentType, "application/json") && (c.Request.Method != http.MethodGet || c.Request.ContentLength != 0) {
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, model.PushResponse{
				Success: false,
				Error:   "Invalid request body",
			})
			return
		}
	} else if strings.Contains(contentType, "application/x-www-form-urlencoded") || strings.Contains(contentType, "multipart/form-data") {
		// Form-encoded (Bark-compatible)
		req.Target = c.PostForm("target")
		req.ID = model.NotificationID(c.PostForm("id"))
		req.Delete = model.PushFlag(c.PostForm("delete"))
		req.Markdown = c.PostForm("markdown")
		req.Title = c.PostForm("title")
		req.Body = c.PostForm("body")
		req.Group = c.PostForm("group")
		req.Icon = c.PostForm("icon")
		req.URL = c.PostForm("url")
		req.Image = c.PostForm("image")
		req.Sound = c.PostForm("sound")
		if v := c.PostForm("badge"); v != "" {
			badge, err := strconv.Atoi(v)
			if err != nil {
				c.JSON(400, model.PushResponse{Error: "badge 必须为整数"})
				return
			}
			req.Badge = badge
		}
		req.Encryption = c.PostForm("encryption")
		req.Nonce = c.PostForm("nonce")
		req.Ciphertext = c.PostForm("ciphertext")
	} else {
		// Try JSON first, fallback to empty
		_ = c.ShouldBindJSON(&req)
	}

	// Path values provide defaults for the simple GET endpoint.
	if title := c.Param("title"); title != "" && req.Title == "" {
		req.Title = title
	}
	if body := c.Param("body"); body != "" && req.Body == "" {
		req.Body = body
	} else if c.Param("title") != "" && req.Body == "" {
		req.Body = c.Param("title")
		req.Title = "Accnotify"
	}

	h.sendPush(c, deviceKey, device, req)
}

// sendPush applies shared overrides, validation, encryption and delivery.
func (h *PushHandler) sendPush(c *gin.Context, deviceKey string, device *model.Device, req model.PushRequest) {
	// Override with query parameters if present (Bark-compatible)
	if v := c.Query("target"); v != "" {
		req.Target = v
	}
	if v := c.Query("id"); v != "" {
		req.ID = model.NotificationID(v)
	}
	if v := c.Query("delete"); v != "" {
		req.Delete = model.PushFlag(v)
	}
	if v := c.Query("markdown"); v != "" {
		req.Markdown = v
	}
	if v := c.Query("title"); v != "" {
		req.Title = v
	}
	if v := c.Query("body"); v != "" {
		req.Body = v
	}
	if v := c.Query("group"); v != "" {
		req.Group = v
	}
	if v := c.Query("icon"); v != "" {
		req.Icon = v
	}
	if v := c.Query("url"); v != "" {
		req.URL = v
	}
	if v := c.Query("image"); v != "" {
		req.Image = v
	}
	if v := c.Query("sound"); v != "" {
		req.Sound = v
	}
	if v := c.Query("encryption"); v != "" {
		req.Encryption = v
	}
	if v := c.Query("nonce"); v != "" {
		req.Nonce = v
	}
	if v := c.Query("ciphertext"); v != "" {
		req.Ciphertext = v
	}

	if v := c.Query("badge"); v != "" {
		badge, err := strconv.Atoi(v)
		if err != nil {
			c.JSON(400, model.PushResponse{Error: "badge 必须为整数"})
			return
		}
		req.Badge = badge
	}

	if len(req.ID) > 256 {
		c.JSON(http.StatusBadRequest, model.PushResponse{Error: "id 不能超过 256 字节"})
		return
	}
	if req.Delete.Enabled() && req.ID == "" {
		c.JSON(http.StatusBadRequest, model.PushResponse{Error: "删除消息必须提供字符串 id"})
		return
	}
	if req.Markdown != "" {
		req.Body = req.Markdown
	}

	// Every delivery has its own ACK ID; the caller's id identifies the notification.
	messageID := uuid.New().String()

	// Replace literal \n with actual newlines
	req.Body = strings.ReplaceAll(req.Body, `\n`, "\n")
	req.Title = strings.ReplaceAll(req.Title, `\n`, "\n")

	// Create message
	msg := &model.Message{
		DeviceID:        device.ID,
		MessageID:       messageID,
		NotificationID:  string(req.ID),
		DeleteRequested: req.Delete.Enabled(),
		Title:           req.Title,
		Body:            req.Body,
		Group:           req.Group,
		Icon:            req.Icon,
		URL:             req.URL,
		Image:           req.Image,
		Sound:           req.Sound,
		Badge:           req.Badge,
	}
	if req.Encryption != "" || req.Nonce != "" || req.Ciphertext != "" {
		if req.Title != "" || req.Body != "" || req.Group != "" || req.Icon != "" || req.URL != "" || req.Image != "" || req.Sound != "" || req.Badge != 0 || req.EncryptedContent != "" || req.Markdown != "" || req.ID != "" || req.Delete.Enabled() {
			c.JSON(http.StatusBadRequest, model.PushResponse{Success: false, Error: "加密请求不能同时包含明文消息字段，请将消息字段放入密文"})
			return
		}
		envelope := senderEnvelope{Encryption: req.Encryption, Nonce: req.Nonce, Ciphertext: req.Ciphertext}
		if err := envelope.validate(); err != nil {
			c.JSON(http.StatusBadRequest, model.PushResponse{Success: false, Error: err.Error()})
			return
		}
		encoded, err := json.Marshal(envelope)
		if err != nil {
			c.JSON(http.StatusInternalServerError, model.PushResponse{Success: false, Error: "密文保存失败"})
			return
		}
		msg.EncryptedPayload = append([]byte(senderEnvelopePrefix), encoded...)
	}

	count, err := deliverToEndpoints(h.storage, h.hub, deviceKey, req.Target, msg)
	if err != nil {
		c.JSON(400, model.PushResponse{Error: err.Error()})
		return
	}
	c.JSON(http.StatusOK, model.PushResponse{Success: true, MessageID: messageID, TargetCount: count})
}

// HandleSimplePush handles GET /push/:device_key/:title/:body (Bark-compatible)
func (h *PushHandler) HandleSimplePush(c *gin.Context) {
	h.HandlePush(c)
}

// HandleRegister handles POST /register
func (h *PushHandler) HandleRegister(c *gin.Context) {
	var req model.RegisterRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"success": false,
			"error":   "Invalid request body",
		})
		return
	}

	if req.Platform == "" {
		req.Platform = "mobile"
	}
	if req.Platform != "mobile" && req.Platform != "pc" {
		c.JSON(400, gin.H{"error": "platform 必须为 mobile 或 pc"})
		return
	}
	// Check if device exists
	device, err := h.storage.GetDeviceByKey(req.DeviceKey)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"success": false,
			"error":   "Database error",
		})
		return
	}

	if device != nil {
		if device.Platform != req.Platform {
			c.JSON(409, gin.H{"error": "设备 ID 已由其他端使用，请生成独立 ID 后绑定"})
			return
		}
		// Update public key
		if req.PublicKey != "" {
			if err := h.storage.UpdateDevicePublicKey(req.DeviceKey, req.PublicKey); err != nil {
				c.JSON(http.StatusInternalServerError, gin.H{
					"success": false,
					"error":   "Failed to update public key",
				})
				return
			}
		}
		c.JSON(http.StatusOK, gin.H{
			"success":     true,
			"device_key":  req.DeviceKey,
			"endpoint_id": device.EndpointID,
			"message":     "Device updated",
		})
		return
	}

	// Create new device
	newDevice := &model.Device{
		Platform:  req.Platform,
		DeviceKey: req.DeviceKey,
		PublicKey: req.PublicKey,
		Name:      req.Name,
	}

	if err := h.storage.CreateDevice(newDevice); err != nil {
		if errors.Is(err, storage.ErrRevokedDeviceKey) {
			c.JSON(http.StatusConflict, gin.H{"success": false, "error": "设备 ID 已注销，请使用新的设备 ID"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{
			"success": false,
			"error":   "Failed to create device",
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"success":     true,
		"device_key":  req.DeviceKey,
		"endpoint_id": newDevice.EndpointID,
		"message":     "Device registered",
	})
}

// HandleHealth handles GET /health
func (h *PushHandler) HandleHealth(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"status":    "ok",
		"timestamp": time.Now().Unix(),
	})
}
