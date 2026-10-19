package handler

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"embed"
	"encoding/hex"
	"fmt"
	"io/fs"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/accnotify/server/logbuffer"
	"github.com/accnotify/server/storage"
	"github.com/gin-gonic/gin"
)

//go:embed adminui/*
var adminFiles embed.FS

type AdminHandler struct {
	storage      *storage.SQLiteStorage
	hub          *Hub
	logs         *logbuffer.Buffer
	usernameHash [32]byte
	passwordHash [32]byte
	secureCookie bool
	mu           sync.Mutex
	sessions     map[string]time.Time
	failedLogins int
	loginWindow  time.Time
	jobs         map[string]*PushJob
	activeJob    string
}

func NewAdminRouter(store *storage.SQLiteStorage, hub *Hub, logs *logbuffer.Buffer, username, password string, secureCookie bool) (http.Handler, error) {
	if username == "" || len(password) < 12 {
		return nil, fmt.Errorf("administrator username is required and password must be at least 12 characters")
	}
	h := &AdminHandler{storage: store, hub: hub, logs: logs, usernameHash: sha256.Sum256([]byte(username)), passwordHash: sha256.Sum256([]byte(password)), secureCookie: secureCookie, sessions: make(map[string]time.Time), jobs: make(map[string]*PushJob)}
	r := gin.New()
	r.Use(gin.LoggerWithConfig(gin.LoggerConfig{SkipPaths: []string{"/admin/api/logs", "/admin/api/dashboard", "/admin/api/devices"}}), gin.Recovery(), h.securityHeaders)
	r.GET("/", func(c *gin.Context) { c.Redirect(http.StatusFound, "/admin/") })
	files, err := fs.Sub(adminFiles, "adminui")
	if err != nil {
		return nil, err
	}
	for _, name := range []string{"index.html", "app.js", "style.css"} {
		content, err := fs.ReadFile(files, name)
		if err != nil {
			return nil, err
		}
		contentType := map[string]string{"index.html": "text/html; charset=utf-8", "app.js": "application/javascript; charset=utf-8", "style.css": "text/css; charset=utf-8"}[name]
		path := "/admin/" + name
		if name == "index.html" {
			path = "/admin/"
		}
		r.GET(path, func(c *gin.Context) { c.Data(http.StatusOK, contentType, content) })
	}
	api := r.Group("/admin/api")
	api.POST("/login", h.login)
	protected := api.Group("", h.authenticate)
	protected.POST("/logout", h.logout)
	protected.GET("/dashboard", h.dashboard)
	protected.GET("/devices", h.devices)
	protected.GET("/logs", h.readLogs)
	protected.POST("/push", h.startPush)
	protected.GET("/push/:id", h.pushStatus)
	return r, nil
}

func (h *AdminHandler) readLogs(c *gin.Context) {
	cursor, err := strconv.ParseInt(c.DefaultQuery("cursor", "-1"), 10, 64)
	if err != nil || cursor < -1 {
		c.JSON(400, gin.H{"error": "日志位置无效"})
		return
	}
	text, next, truncated := h.logs.ReadSince(cursor)
	c.JSON(200, gin.H{"text": text, "cursor": next, "truncated": truncated, "instance": h.logs.Instance()})
}

func (h *AdminHandler) securityHeaders(c *gin.Context) {
	c.Header("Cache-Control", "no-store")
	c.Header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
	c.Header("X-Content-Type-Options", "nosniff")
	c.Header("Referrer-Policy", "no-referrer")
	c.Next()
}

func (h *AdminHandler) login(c *gin.Context) {
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 4096)
	var req struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if c.ShouldBindJSON(&req) != nil {
		c.JSON(400, gin.H{"error": "请输入账号和密码"})
		return
	}
	userHash, passHash := sha256.Sum256([]byte(req.Username)), sha256.Sum256([]byte(req.Password))
	now := time.Now()
	h.mu.Lock()
	defer h.mu.Unlock()
	if now.Sub(h.loginWindow) >= time.Minute {
		h.failedLogins = 0
		h.loginWindow = now
	}
	if h.failedLogins >= 10 {
		c.Header("Retry-After", "60")
		c.JSON(429, gin.H{"error": "登录尝试过多，请稍后重试"})
		return
	}
	userMatch := subtle.ConstantTimeCompare(userHash[:], h.usernameHash[:])
	passMatch := subtle.ConstantTimeCompare(passHash[:], h.passwordHash[:])
	if userMatch&passMatch != 1 {
		h.failedLogins++
		c.JSON(401, gin.H{"error": "账号或密码不正确"})
		return
	}
	for token, expires := range h.sessions {
		if !expires.After(now) {
			delete(h.sessions, token)
		}
	}
	if len(h.sessions) >= 64 {
		c.JSON(429, gin.H{"error": "登录会话过多，请先退出其他会话"})
		return
	}
	var random [32]byte
	if _, err := rand.Read(random[:]); err != nil {
		c.JSON(500, gin.H{"error": "登录失败，请稍后重试"})
		return
	}
	token := hex.EncodeToString(random[:])
	h.sessions[token] = now.Add(12 * time.Hour)
	h.failedLogins = 0
	h.setCookie(c, token, 12*60*60)
	c.JSON(200, gin.H{"success": true})
}

func (h *AdminHandler) setCookie(c *gin.Context, token string, maxAge int) {
	http.SetCookie(c.Writer, &http.Cookie{Name: "accnotify_admin", Value: token, Path: "/admin", HttpOnly: true, Secure: h.secureCookie || c.Request.TLS != nil, SameSite: http.SameSiteStrictMode, MaxAge: maxAge})
}

func (h *AdminHandler) authenticate(c *gin.Context) {
	token, err := c.Cookie("accnotify_admin")
	h.mu.Lock()
	expires, ok := h.sessions[token]
	if ok && !expires.After(time.Now()) {
		delete(h.sessions, token)
		ok = false
	}
	h.mu.Unlock()
	if err != nil || !ok {
		c.AbortWithStatusJSON(401, gin.H{"error": "请先登录管理员后台"})
		return
	}
	c.Next()
}

func (h *AdminHandler) logout(c *gin.Context) {
	token, _ := c.Cookie("accnotify_admin")
	h.mu.Lock()
	delete(h.sessions, token)
	h.mu.Unlock()
	h.setCookie(c, "", -1)
	c.JSON(200, gin.H{"success": true})
}

func (h *AdminHandler) dashboard(c *gin.Context) {
	stats, err := h.storage.DashboardStats(time.Now())
	if err != nil {
		c.JSON(500, gin.H{"error": "统计数据读取失败"})
		return
	}
	stats.OnlineDevices = h.hub.OnlineCount()
	c.JSON(200, stats)
}

func (h *AdminHandler) devices(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "50"))
	if page < 1 || page > 1000000 {
		page = 1
	}
	if limit < 1 || limit > 100 {
		limit = 50
	}
	devices, total, err := h.storage.ListDevices(strings.TrimSpace(c.Query("q")), limit, (page-1)*limit)
	if err != nil {
		c.JSON(500, gin.H{"error": "设备列表读取失败"})
		return
	}
	items := make([]gin.H, 0, len(devices))
	for _, d := range devices {
		hint := "已隐藏"
		if len(d.DeviceKey) > 8 {
			hint = "…" + d.DeviceKey[len(d.DeviceKey)-8:]
		}
		pushHint := "已隐藏"
		if len(d.PushKey) > 8 {
			pushHint = "…" + d.PushKey[len(d.PushKey)-8:]
		}
		items = append(items, gin.H{"push_group_id": d.PushGroupID, "push_key_hint": pushHint, "id": d.ID, "name": d.Name, "platform": d.Platform, "endpoint_id": d.EndpointID, "key_hint": hint, "online": h.hub.IsOnline(d.DeviceKey), "has_public_key": d.PublicKey != "", "created_at": d.CreatedAt, "last_seen": d.LastSeen})
	}
	c.JSON(200, gin.H{"devices": items, "total": total, "page": page, "limit": limit})
}
