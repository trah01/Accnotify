package handler

import (
	"bytes"
	"crypto"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"encoding/pem"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/accnotify/server/logbuffer"
	"github.com/accnotify/server/model"
	"github.com/accnotify/server/storage"
	"github.com/gin-gonic/gin"
)

func TestAdminAccessAndPush(t *testing.T) {
	store, err := storage.NewSQLiteStorage(filepath.Join(t.TempDir(), "existing.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	keys := make([]*rsa.PrivateKey, 2)
	devices := make([]*model.Device, 3)
	for i := range devices {
		device := &model.Device{DeviceKey: "private-device-credential-" + string(rune('a'+i)), Name: "test-device"}
		if i < 2 {
			keys[i], err = rsa.GenerateKey(rand.Reader, 2048)
			if err != nil {
				t.Fatal(err)
			}
			der, err := x509.MarshalPKIXPublicKey(&keys[i].PublicKey)
			if err != nil {
				t.Fatal(err)
			}
			device.PublicKey = string(pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: der}))
		}
		if err := store.CreateDevice(device); err != nil {
			t.Fatal(err)
		}
		devices[i] = device
	}
	hub := NewHub(store)
	var output bytes.Buffer
	logs := logbuffer.New(&output, 1024)
	logs.Write([]byte("server started\n原始日志\n"))
	previous := gin.DefaultWriter
	gin.DefaultWriter = io.Discard
	defer func() { gin.DefaultWriter = previous }()
	gin.SetMode(gin.TestMode)
	router, err := NewAdminRouter(store, hub, logs, "admin", "test-only-admin-password", false)
	if err != nil {
		t.Fatal(err)
	}
	request := func(method, path, body string, cookie *http.Cookie, origin, header string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, path, strings.NewReader(body))
		r.Header.Set("Content-Type", "application/json")
		if header != "" {
			r.Header.Set("X-Admin-Request", header)
		}
		if origin != "" {
			r.Header.Set("Origin", origin)
		}
		if cookie != nil {
			r.AddCookie(cookie)
		}
		w := httptest.NewRecorder()
		router.ServeHTTP(w, r)
		return w
	}
	for _, path := range []string{"/admin/api/dashboard", "/admin/api/devices", "/admin/api/logs"} {
		if response := request("GET", path, "", nil, "", ""); response.Code != 401 {
			t.Fatalf("unauthorized %s: %d", path, response.Code)
		}
	}
	login := `{"username":"admin","password":"test-only-admin-password"}`
	if response := request("POST", "/admin/api/login", `{"username":"admin","password":"wrong"}`, nil, "", "1"); response.Code != 401 {
		t.Fatal("incorrect password was accepted")
	}
	response := request("POST", "/admin/api/login", login, nil, "https://admin.example.com", "")
	if response.Code != 200 {
		t.Fatal(response.Body.String())
	}
	cookies := response.Result().Cookies()
	if len(cookies) != 1 || !cookies[0].HttpOnly || cookies[0].SameSite != http.SameSiteStrictMode || cookies[0].Path != "/admin" {
		t.Fatal("unsafe session cookie")
	}
	cookie := cookies[0]
	response = request("GET", "/admin/api/devices?limit=1&page=2&q=test", "", cookie, "", "")
	var list struct {
		Devices []json.RawMessage `json:"devices"`
		Total   int               `json:"total"`
	}
	if response.Code != 200 || json.Unmarshal(response.Body.Bytes(), &list) != nil || list.Total != 3 || len(list.Devices) != 1 {
		t.Fatalf("pagination failed: %s", response.Body.String())
	}
	for _, device := range devices {
		if strings.Contains(response.Body.String(), device.DeviceKey) || strings.Contains(response.Body.String(), "BEGIN PUBLIC KEY") {
			t.Fatal("device credentials leaked")
		}
	}
	response = request("GET", "/admin/api/logs", "", cookie, "", "")
	var logResponse struct {
		Text   string `json:"text"`
		Cursor int64  `json:"cursor"`
	}
	if response.Code != 200 || json.Unmarshal(response.Body.Bytes(), &logResponse) != nil || logResponse.Text != output.String() {
		t.Fatal("admin logs differ from stdout")
	}
	for _, body := range []string{`{"target":"all","body":"secret"}`, `{"target":"device","device_id":999,"body":"secret"}`, `{"target":"devices","device_ids":[],"body":"secret"}`, `{"target":"devices","device_ids":[1,999],"body":"secret"}`, `{"target":"devices","device_ids":[0],"body":"secret"}`, `{"target":"unknown","body":"secret"}`} {
		response = request("POST", "/admin/api/push", body, cookie, "", "1")
		if response.Code < 400 {
			t.Fatal("invalid send request was accepted")
		}
	}
	waitJob := func(body string) PushJob {
		response := request("POST", "/admin/api/push", body, cookie, "", "1")
		var result struct {
			ID string `json:"job_id"`
		}
		if response.Code != 202 || json.Unmarshal(response.Body.Bytes(), &result) != nil {
			t.Fatalf("push failed: %s", response.Body.String())
		}
		deadline := time.Now().Add(3 * time.Second)
		for time.Now().Before(deadline) {
			response = request("GET", "/admin/api/push/"+result.ID, "", cookie, "", "")
			var job PushJob
			if response.Code != 200 || json.Unmarshal(response.Body.Bytes(), &job) != nil {
				t.Fatal(response.Body.String())
			}
			if job.Status == "completed" {
				return job
			}
			time.Sleep(time.Millisecond)
		}
		t.Fatal("push job did not finish")
		return PushJob{}
	}
	job := waitJob(`{"target":"device","device_id":1,"title":"single","body":"secret","group":"test","url":"https://example.com","image":"image-data"}`)
	if job.Total != 1 || job.Submitted != 1 || job.Offline != 1 || job.Failed != 0 {
		t.Fatalf("single push result: %+v", job)
	}
	job = waitJob(`{"target":"devices","device_ids":[1,2,1],"title":"selected","body":"secret"}`)
	if job.Total != 2 || job.Processed != 2 || job.Submitted != 2 || job.Offline != 2 || job.Failed != 0 {
		t.Fatalf("selected devices push result: %+v", job)
	}
	if messages, err := store.GetUndeliveredMessages(devices[2].ID); err != nil || len(messages) != 0 {
		t.Fatal("unselected device received a message")
	}
	hub.mu.Lock()
	hub.clients[devices[0].DeviceKey] = &Client{deviceKey: devices[0].DeviceKey, deviceID: devices[0].ID, send: make(chan []byte, 10)}
	hub.mu.Unlock()
	job = waitJob(`{"target":"all","confirm_all":true,"title":"global","body":"secret","image":"image-data"}`)
	if job.Total != 3 || job.Processed != 3 || job.Submitted != 2 || job.Offline != 1 || job.Online != 1 || job.Failed != 1 || job.Failures[0].DeviceID != devices[2].ID {
		t.Fatalf("global push result: %+v", job)
	}
	var ciphertexts []string
	for i := 0; i < 2; i++ {
		messages, err := store.GetUndeliveredMessages(devices[i].ID)
		if err != nil {
			t.Fatal(err)
		}
		last := messages[len(messages)-1]
		payload := decryptAdminTestMessage(t, string(last.EncryptedPayload), keys[i])
		if payload["title"] != "global" || payload["body"] != "secret" || payload["image"] != "image-data" {
			t.Fatal("notification fields not preserved")
		}
		ciphertexts = append(ciphertexts, string(last.EncryptedPayload))
		wire, _ := json.Marshal(encryptedWSMessage(last))
		if strings.Contains(string(wire), "secret") || strings.Contains(string(wire), "image-data") {
			t.Fatal("plaintext leaked")
		}
	}
	if ciphertexts[0] == ciphertexts[1] {
		t.Fatal("global push reused ciphertext for different devices")
	}
	response = request("GET", "/admin/api/dashboard", "", cookie, "", "")
	var stats storage.Dashboard
	if response.Code != 200 || json.Unmarshal(response.Body.Bytes(), &stats) != nil || stats.Devices != 3 || stats.Messages != 5 || stats.PendingMessages != 5 {
		t.Fatalf("incorrect stats: %s", response.Body.String())
	}
	for _, body := range []string{
		`{"target":"device","device_id":1,"delete":"1"}`,
		`{"target":"device","device_id":1,"id":42,"delete":"1"}`,
		`{"target":"device","device_id":1,"id":"   ","delete":"1"}`,
		`{"target":"device","device_id":1,"markdown":"   "}`,
		`{"target":"device","device_id":1,"body":"text","id":"` + strings.Repeat("x", 257) + `"}`,
	} {
		if response := request("POST", "/admin/api/push", body, cookie, "", "1"); response.Code != 400 {
			t.Fatalf("invalid message operation accepted: %s", response.Body.String())
		}
	}
	for _, body := range []string{
		`{"target":"device","device_id":1,"id":"admin-status","body":"fallback","markdown":"**first**","group":"work"}`,
		`{"target":"device","device_id":1,"id":"admin-status","markdown":"**updated**","group":"work"}`,
		`{"target":"device","device_id":1,"id":"admin-status","delete":"1"}`,
	} {
		if job := waitJob(body); job.Submitted != 1 || job.Failed != 0 {
			t.Fatalf("message operation failed: %+v", job)
		}
	}
	messages, err := store.GetUndeliveredMessages(devices[0].ID)
	if err != nil || len(messages) != 6 {
		t.Fatalf("unexpected queued operations: %d, %v", len(messages), err)
	}
	for i, msg := range messages[3:] {
		payload := decryptAdminTestMessage(t, string(msg.EncryptedPayload), keys[0])
		if msg.NotificationID != "admin-status" || payload["id"] != "admin-status" {
			t.Fatal("message ID not preserved in storage and encrypted payload")
		}
		if i < 2 {
			if payload["body"] != []string{"**first**", "**updated**"}[i] || payload["group"] != "work" {
				t.Fatal("Markdown or group not preserved")
			}
		} else if !msg.DeleteRequested || payload["delete"] != "1" {
			t.Fatal("delete operation not preserved")
		}
		if i > 0 && msg.MessageID == messages[3+i-1].MessageID {
			t.Fatal("message operations reused a delivery ID")
		}
	}
	for _, path := range []string{"/admin/", "/admin/app.js", "/admin/style.css"} {
		if response := request("GET", path, "", nil, "", ""); response.Code != 200 {
			t.Fatalf("missing UI: %s", path)
		}
	}
	if response := request("POST", "/admin/api/logout", "", cookie, "", "1"); response.Code != 200 {
		t.Fatal("logout failed")
	}
	if response := request("GET", "/admin/api/dashboard", "", cookie, "", ""); response.Code != 401 {
		t.Fatal("logged-out session remained valid")
	}
	if _, err := NewAdminRouter(store, hub, logs, "admin", "", false); err == nil {
		t.Fatal("empty password accepted")
	}
	if _, err := NewAdminRouter(store, hub, logs, "admin", "short", false); err == nil {
		t.Fatal("short password accepted")
	}
}

func decryptAdminTestMessage(t *testing.T, encoded string, key *rsa.PrivateKey) map[string]interface{} {
	t.Helper()
	data, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		t.Fatal(err)
	}
	keyLen := int(binary.BigEndian.Uint16(data[:2]))
	aesKey, err := key.Decrypt(rand.Reader, data[2:2+keyLen], &rsa.OAEPOptions{Hash: crypto.SHA256, MGFHash: crypto.SHA1})
	if err != nil {
		t.Fatal(err)
	}
	block, err := aes.NewCipher(aesKey)
	if err != nil {
		t.Fatal(err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		t.Fatal(err)
	}
	plaintext, err := gcm.Open(nil, data[2+keyLen:2+keyLen+12], data[2+keyLen+12:], nil)
	if err != nil {
		t.Fatal(err)
	}
	var result map[string]interface{}
	if err := json.Unmarshal(plaintext, &result); err != nil {
		t.Fatal(err)
	}
	return result
}
