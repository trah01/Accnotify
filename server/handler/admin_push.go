package handler

import (
	"net/http"
	"strings"
	"time"

	"github.com/accnotify/server/model"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

type PushFailure struct {
	DeviceID int64  `json:"device_id"`
	Error    string `json:"error"`
}

type PushJob struct {
	ID        string        `json:"id"`
	Status    string        `json:"status"`
	Total     int           `json:"total"`
	Processed int           `json:"processed"`
	Submitted int           `json:"submitted"`
	Online    int           `json:"online"`
	Offline   int           `json:"offline"`
	Failed    int           `json:"failed"`
	Failures  []PushFailure `json:"failures"`
	CreatedAt time.Time     `json:"created_at"`
}

func (h *AdminHandler) startPush(c *gin.Context) {
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 8*1024*1024)
	var req struct {
		Target     string  `json:"target"`
		DeviceID   int64   `json:"device_id"`
		DeviceIDs  []int64 `json:"device_ids"`
		ConfirmAll bool    `json:"confirm_all"`
		model.PushRequest
	}
	if c.ShouldBindJSON(&req) != nil {
		c.JSON(400, gin.H{"error": "请求格式无效"})
		return
	}
	if len(req.ID) > 256 {
		c.JSON(400, gin.H{"error": "消息 ID 不能超过 256 字节"})
		return
	}
	if req.Delete.Enabled() && strings.TrimSpace(string(req.ID)) == "" {
		c.JSON(400, gin.H{"error": "删除消息必须填写消息 ID"})
		return
	}
	if req.Markdown != "" {
		req.Body = req.Markdown
	}
	if !req.Delete.Enabled() && strings.TrimSpace(req.Body) == "" {
		c.JSON(400, gin.H{"error": "请填写消息正文"})
		return
	}
	if req.EncryptedContent != "" || req.Encryption != "" || req.Nonce != "" || req.Ciphertext != "" {
		c.JSON(400, gin.H{"error": "请填写明文消息，由服务端完成加密"})
		return
	}
	var ids []int64
	switch req.Target {
	case "all":
		if !req.ConfirmAll {
			c.JSON(400, gin.H{"error": "请确认向全部设备发送通知"})
			return
		}
		var err error
		ids, err = h.storage.DeviceIDs()
		if err != nil {
			c.JSON(500, gin.H{"error": "设备列表读取失败"})
			return
		}
	case "device":
		device, err := h.storage.GetDeviceByID(req.DeviceID)
		if err != nil {
			c.JSON(500, gin.H{"error": "设备读取失败"})
			return
		}
		if device == nil {
			c.JSON(404, gin.H{"error": "设备不存在"})
			return
		}
		ids = []int64{device.ID}
	case "devices":
		seen := make(map[int64]bool)
		for _, id := range req.DeviceIDs {
			if id <= 0 {
				c.JSON(400, gin.H{"error": "接收设备无效"})
				return
			}
			if seen[id] {
				continue
			}
			device, err := h.storage.GetDeviceByID(id)
			if err != nil {
				c.JSON(500, gin.H{"error": "设备读取失败"})
				return
			}
			if device == nil {
				c.JSON(404, gin.H{"error": "所选设备不存在，请刷新设备列表"})
				return
			}
			seen[id] = true
			ids = append(ids, id)
		}
	default:
		c.JSON(400, gin.H{"error": "请选择指定设备或全部设备"})
		return
	}
	if len(ids) == 0 {
		c.JSON(400, gin.H{"error": "没有可接收通知的设备"})
		return
	}
	job := &PushJob{ID: uuid.NewString(), Status: "sending", Total: len(ids), Failures: make([]PushFailure, 0), CreatedAt: time.Now()}
	h.mu.Lock()
	if h.activeJob != "" {
		active := h.activeJob
		h.mu.Unlock()
		c.JSON(409, gin.H{"error": "已有通知正在发送，请等待完成", "job_id": active})
		return
	}
	if len(h.jobs) >= 20 {
		var oldest *PushJob
		for _, candidate := range h.jobs {
			if oldest == nil || candidate.CreatedAt.Before(oldest.CreatedAt) {
				oldest = candidate
			}
		}
		delete(h.jobs, oldest.ID)
	}
	h.jobs[job.ID] = job
	h.activeJob = job.ID
	h.mu.Unlock()
	// Respond before processing a potentially large recipient list.
	c.JSON(http.StatusAccepted, gin.H{"job_id": job.ID, "total": job.Total})
	go h.runPush(job.ID, ids, req.PushRequest)
}

func (h *AdminHandler) runPush(id string, ids []int64, req model.PushRequest) {
	for _, deviceID := range ids {
		failure := ""
		online := false
		device, err := h.storage.GetDeviceByID(deviceID)
		if err != nil {
			failure = "设备读取失败"
		} else if device == nil {
			failure = "设备不存在"
		} else {
			msg := &model.Message{
				DeviceID: device.ID, MessageID: uuid.NewString(),
				NotificationID: string(req.ID), DeleteRequested: req.Delete.Enabled(),
				Title: strings.ReplaceAll(req.Title, `\n`, "\n"), Body: strings.ReplaceAll(req.Body, `\n`, "\n"),
				Group: req.Group, URL: req.URL, Image: req.Image, Icon: req.Icon, Sound: req.Sound, Badge: req.Badge,
			}
			if encryptDeviceMessage(device, msg) != nil {
				failure = "设备未上报有效公钥或加密失败"
			} else if h.storage.CreateMessage(msg) != nil {
				failure = "消息保存失败"
			} else {
				online = h.hub.SendToDevice(device.DeviceKey, encryptedWSMessage(msg))
			}
		}
		h.mu.Lock()
		job := h.jobs[id]
		job.Processed++
		if failure != "" {
			job.Failed++
			if len(job.Failures) < 20 {
				job.Failures = append(job.Failures, PushFailure{DeviceID: deviceID, Error: failure})
			}
		} else {
			job.Submitted++
			if online {
				job.Online++
			} else {
				job.Offline++
			}
		}
		h.mu.Unlock()
	}
	h.mu.Lock()
	h.jobs[id].Status = "completed"
	h.activeJob = ""
	h.mu.Unlock()
}

func (h *AdminHandler) pushStatus(c *gin.Context) {
	h.mu.Lock()
	defer h.mu.Unlock()
	job, ok := h.jobs[c.Param("id")]
	if !ok {
		c.JSON(404, gin.H{"error": "发送记录不存在或服务端已重启"})
		return
	}
	c.JSON(200, job)
}
