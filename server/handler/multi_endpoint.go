package handler

import (
	"crypto/rand"
	"crypto/subtle"
	"errors"
	"math/big"
	"net/http"
	"regexp"
	"strings"

	"github.com/accnotify/server/model"
	"github.com/accnotify/server/storage"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

var alphanumericID = regexp.MustCompile(`^[A-Za-z0-9]{32}$`)

// Create all deliveries before publishing any of them. Each physical recipient
// keeps a separate ciphertext, sequence, ACK and expiration lifecycle.
func deliverToEndpoints(store *storage.SQLiteStorage, hub *Hub, key, target string, source *model.Message) (int, error) {
	devices, err := store.Recipients(key, target)
	if err != nil {
		return 0, err
	}
	messages := make([]*model.Message, 0, len(devices))
	for i, d := range devices {
		m := *source
		m.ID = 0
		m.DeviceID = d.ID
		if i > 0 {
			m.MessageID = uuid.NewString()
		}
		if err = encryptDeviceMessage(d, &m); err != nil {
			return 0, errors.New("接收端公钥无效，请重新同步设备")
		}
		messages = append(messages, &m)
	}
	if err = store.CreateEndpointMessages(messages); err != nil {
		return 0, err
	}
	for i, d := range devices {
		hub.SendToDevice(d.DeviceKey, encryptedWSMessage(messages[i]))
	}
	return len(messages), nil
}

func (h *PushHandler) bindingActor(c *gin.Context) *model.Device {
	key := strings.TrimPrefix(c.GetHeader("Authorization"), "Bearer ")
	if key == "" || len(key) > 256 || subtle.ConstantTimeCompare([]byte(c.GetHeader("Authorization")), []byte("Bearer "+key)) != 1 {
		c.JSON(401, gin.H{"error": "设备凭据无效"})
		return nil
	}
	d, err := h.storage.GetDeviceByKey(key)
	if err != nil {
		c.JSON(500, gin.H{"error": "设备读取失败"})
		return nil
	}
	if d == nil {
		c.JSON(401, gin.H{"error": "设备已被移除，请重新注册"})
		return nil
	}
	return d
}
func bindingError(c *gin.Context, err error) {
	status := 500
	if errors.Is(err, storage.ErrBinding) {
		status = 400
	}
	if errors.Is(err, storage.ErrBindingRate) {
		status = 429
	}
	c.JSON(status, gin.H{"success": false, "error": err.Error()})
}

func (h *PushHandler) HandleBindingRequest(c *gin.Context) {
	actor := h.bindingActor(c)
	if actor == nil {
		return
	}
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 4096)
	var req struct {
		MobileKey       string `json:"mobile_key"`
		GroupKey        string `json:"group_key"`
		ConfirmEndpoint string `json:"confirm_endpoint"`
	}
	if c.ShouldBindJSON(&req) != nil || len(req.MobileKey) > 256 {
		c.JSON(400, gin.H{"error": "手机链接无效"})
		return
	}
	if req.GroupKey != "" {
		req.MobileKey = req.GroupKey
	}
	recipients, err := h.storage.Recipients(req.MobileKey, req.ConfirmEndpoint)
	var mobile *model.Device
	if err == nil {
		mobile = recipients[0]
		if req.ConfirmEndpoint == "" {
			for _, d := range recipients {
				if d.Platform == "mobile" {
					mobile = d
					break
				}
			}
		}
	}
	if err != nil {
		bindingError(c, err)
		return
	}
	if mobile == nil {
		c.JSON(400, gin.H{"error": "确认设备不存在，请检查服务器和推送链接"})
		return
	}
	n, err := rand.Int(rand.Reader, big.NewInt(1000000))
	if err != nil {
		bindingError(c, err)
		return
	}
	code := formatCode(n.Int64())
	id := uuid.NewString()
	if err = h.storage.CreateBindingRequest(id, actor, mobile, code); err != nil {
		bindingError(c, err)
		return
	}
	msg := &model.Message{MessageID: uuid.NewString(), Title: "绑定设备", Body: "设备：" + actor.Name + "（" + actor.EndpointID + "）\n绑定验证码：" + code + "\n5 分钟内有效。请在发起绑定的电脑输入；如果不是你操作，请忽略。", Group: "device"}
	if _, err = deliverToEndpoints(h.storage, h.hub, req.MobileKey, mobile.EndpointID, msg); err != nil {
		bindingError(c, err)
		return
	}
	c.JSON(200, gin.H{"success": true, "request_id": id, "expires_in": 300, "confirm_name": mobile.Name, "confirm_endpoint": mobile.EndpointID})
}
func formatCode(n int64) string {
	b := []byte("000000")
	for i := 5; i >= 0; i-- {
		b[i] = byte('0' + n%10)
		n /= 10
	}
	return string(b)
}

func (h *PushHandler) HandleBindingVerify(c *gin.Context) {
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 4096)
	var req struct {
		RequestID string `json:"request_id"`
		Code      string `json:"code"`
		NewKey    string `json:"new_device_key"`
	}
	if c.ShouldBindJSON(&req) != nil || len(req.RequestID) > 64 || len(req.Code) != 6 || !alphanumericID.MatchString(req.NewKey) {
		c.JSON(400, gin.H{"error": "验证码或设备参数无效"})
		return
	}
	key := strings.TrimPrefix(c.GetHeader("Authorization"), "Bearer ")
	if key == "" || len(key) > 256 {
		c.JSON(401, gin.H{"error": "设备凭据无效"})
		return
	}
	pushKey, err := h.storage.VerifyBinding(req.RequestID, key, req.Code, req.NewKey)
	if err != nil {
		bindingError(c, err)
		return
	}
	h.hub.DisconnectDevice(key)
	c.JSON(200, gin.H{"success": true, "device_key": req.NewKey, "push_key": pushKey})
}
func (h *PushHandler) HandleBindingStatus(c *gin.Context) {
	actor := h.bindingActor(c)
	if actor == nil {
		return
	}
	key, list, err := h.storage.BindingStatus(actor)
	if err != nil {
		bindingError(c, err)
		return
	}
	c.JSON(200, gin.H{"success": true, "push_key": key, "bound": key != actor.DeviceKey, "devices": list, "endpoint_id": actor.EndpointID})
}
func (h *PushHandler) HandleBindingRemove(c *gin.Context) {
	actor := h.bindingActor(c)
	if actor == nil {
		return
	}
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 1024)
	var req struct {
		DeviceID int64 `json:"device_id"`
	}
	if c.ShouldBindJSON(&req) != nil {
		c.JSON(400, gin.H{"error": "设备参数无效"})
		return
	}
	if req.DeviceID == 0 {
		req.DeviceID = actor.ID
	}
	key, err := h.storage.RemoveBinding(actor, req.DeviceID)
	if err != nil {
		bindingError(c, err)
		return
	}
	h.hub.DisconnectDevice(key)
	c.JSON(200, gin.H{"success": true})
}
