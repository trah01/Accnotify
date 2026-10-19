package handler

import (
	"crypto/subtle"
	"errors"
	"net/http"

	messagecrypto "github.com/accnotify/server/crypto"
	"github.com/accnotify/server/storage"
	"github.com/gin-gonic/gin"
)

// Device keys are bearer credentials, as with registration and push endpoints.
// Keep them in the request body/header so management URLs do not expose them.
func (h *PushHandler) HandleManageDevice(c *gin.Context) {
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 32*1024)
	var req struct {
		Action            string `json:"action"`
		DeviceKey         string `json:"device_key"`
		ExpectedPublicKey string `json:"expected_public_key"`
		NewDeviceKey      string `json:"new_device_key"`
		PublicKey         string `json:"public_key"`
	}
	if c.ShouldBindJSON(&req) != nil || req.DeviceKey == "" || len(req.DeviceKey) > 256 {
		c.JSON(400, gin.H{"error": "设备参数无效"})
		return
	}
	if subtle.ConstantTimeCompare([]byte(c.GetHeader("Authorization")), []byte("Bearer "+req.DeviceKey)) != 1 {
		c.JSON(401, gin.H{"error": "设备凭据无效"})
		return
	}
	switch req.Action {
	case "reset_rsa", "reset_all":
		pub, err := messagecrypto.NewCrypto().ParsePublicKey(req.PublicKey)
		if err != nil || pub.N.BitLen() < 2048 || pub.N.BitLen() > 4096 || req.PublicKey == req.ExpectedPublicKey {
			c.JSON(400, gin.H{"error": "新的 RSA 公钥无效"})
			return
		}
		if req.Action == "reset_all" && (!alphanumericID.MatchString(req.NewDeviceKey) || req.NewDeviceKey == req.DeviceKey) {
			c.JSON(400, gin.H{"error": "新的设备 ID 无效"})
			return
		}
	case "unregister":
	default:
		c.JSON(400, gin.H{"error": "设备操作无效"})
		return
	}
	if err := h.storage.ManageDevice(req.DeviceKey, req.ExpectedPublicKey, req.Action, req.NewDeviceKey, req.PublicKey); err != nil {
		if errors.Is(err, storage.ErrDeviceConflict) || errors.Is(err, storage.ErrRevokedDeviceKey) {
			c.JSON(409, gin.H{"error": "设备状态已变化，请确认当前服务器和设备密钥"})
		} else {
			c.JSON(500, gin.H{"error": "设备操作失败，未完成时请重试原操作"})
		}
		return
	}
	h.hub.DisconnectDevice(req.DeviceKey)
	c.JSON(200, gin.H{"success": true})
}
