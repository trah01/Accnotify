package handler

import (
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"strings"

	"github.com/accnotify/server/model"
	"github.com/accnotify/server/storage"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

type WebhookHandler struct {
	storage *storage.SQLiteStorage
	hub     *Hub
}

func NewWebhookHandler(store *storage.SQLiteStorage, hub *Hub) *WebhookHandler {
	return &WebhookHandler{storage: store, hub: hub}
}
func (h *WebhookHandler) HandleGenericWebhook(c *gin.Context)   { h.handleWebhook(c, "") }
func (h *WebhookHandler) HandleGitHubWebhook(c *gin.Context)    { h.handleWebhook(c, "GitHub") }
func (h *WebhookHandler) HandleGitLabWebhook(c *gin.Context)    { h.handleWebhook(c, "GitLab") }
func (h *WebhookHandler) HandleDockerHubWebhook(c *gin.Context) { h.handleWebhook(c, "Docker Hub") }
func (h *WebhookHandler) HandleGiteaWebhook(c *gin.Context)     { h.handleWebhook(c, "Gitea") }
func (h *WebhookHandler) HandleForgejoWebhook(c *gin.Context)   { h.handleWebhook(c, "Forgejo") }
func (h *WebhookHandler) HandleFeishuWebhook(c *gin.Context)    { h.handleWebhook(c, "飞书") }
func (h *WebhookHandler) HandleDingtalkWebhook(c *gin.Context)  { h.handleWebhook(c, "钉钉") }
func (h *WebhookHandler) HandleWecomWebhook(c *gin.Context)     { h.handleWebhook(c, "企业微信") }

func (h *WebhookHandler) handleWebhook(c *gin.Context, provider string) {
	deviceKey := c.Param("device_key")
	device, err := h.storage.GetPushDevice(deviceKey)
	if err != nil {
		c.JSON(500, model.PushResponse{Error: "Database error"})
		return
	}
	if deviceKey == "" || device == nil {
		c.JSON(404, model.PushResponse{Error: "Device not found"})
		return
	}
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 8*1024*1024)
	body, err := io.ReadAll(c.Request.Body)
	if err != nil {
		c.JSON(400, model.PushResponse{Error: "Failed to read webhook body or body too large"})
		return
	}
	if len(strings.TrimSpace(string(body))) == 0 {
		c.JSON(400, model.PushResponse{Error: "Webhook body is empty"})
		return
	}
	if strings.HasPrefix(c.ContentType(), "application/x-www-form-urlencoded") {
		form, err := url.ParseQuery(string(body))
		if err != nil {
			c.JSON(400, model.PushResponse{Error: "Invalid webhook form"})
			return
		}
		if payload := form.Get("payload"); payload != "" {
			body = []byte(payload)
		} else {
			data := make(map[string]string)
			for key := range form {
				data[key] = form.Get(key)
			}
			body, _ = json.Marshal(data)
		}
	}
	var payload interface{}
	decoder := json.NewDecoder(strings.NewReader(string(body)))
	decoder.UseNumber()
	decodeErr := decoder.Decode(&payload)
	var extra interface{}
	if decodeErr == nil && decoder.Decode(&extra) != io.EOF {
		decodeErr = io.ErrUnexpectedEOF
	}
	if decodeErr != nil {
		if provider != "" || strings.Contains(c.ContentType(), "json") || strings.HasPrefix(strings.TrimSpace(string(body)), "{") || strings.HasPrefix(strings.TrimSpace(string(body)), "[") {
			c.JSON(400, model.PushResponse{Error: "Invalid webhook JSON"})
			return
		}
		h.sendWebhookMessage(deviceKey, device, "Webhook", string(body), "", "", c)
		return
	}
	data, ok := payload.(map[string]interface{})
	if !ok || data == nil {
		if provider != "" {
			c.JSON(400, model.PushResponse{Error: "Webhook JSON must be an object"})
			return
		}
		formatted, _ := json.MarshalIndent(payload, "", "  ")
		h.sendWebhookMessage(deviceKey, device, "Webhook", string(formatted), "", "", c)
		return
	}
	event := webhookEvent(c.Request.Header, data)
	if provider == "" {
		provider = detectWebhookProvider(c.Request.Header, data)
	}
	message := formatWebhook(provider, event, data)
	raw, _ := json.MarshalIndent(data, "", "  ")
	if message.body == "" {
		message.body = string(raw)
	} else if message.body != string(raw) {
		message.body += "\n\n--- 完整数据 ---\n" + string(raw)
	}
	h.sendWebhookMessage(deviceKey, device, message.title, message.body, message.image, message.url, c)
}

// sendWebhookMessage sends a webhook message to device
func (h *WebhookHandler) sendWebhookMessage(deviceKey string, device *model.Device, title, body, image, targetURL string, c *gin.Context) {
	messageID := uuid.New().String()

	msg := &model.Message{
		DeviceID:  device.ID,
		MessageID: messageID,
		Title:     title,
		Body:      body,
		Group:     "webhook",
		URL:       targetURL,
		Image:     image,
	}

	count, err := deliverToEndpoints(h.storage, h.hub, deviceKey, c.Query("target"), msg)
	if err != nil {
		c.JSON(http.StatusBadRequest, model.PushResponse{Error: err.Error()})
		return
	}
	c.JSON(http.StatusOK, model.PushResponse{Success: true, MessageID: messageID, TargetCount: count})
}
