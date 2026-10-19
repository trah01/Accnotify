package handler

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"net/http/httptest"
	"path/filepath"
	"regexp"
	"strings"
	"testing"

	messagecrypto "github.com/accnotify/server/crypto"
	"github.com/accnotify/server/model"
	"github.com/accnotify/server/storage"
	"github.com/gin-gonic/gin"
)

func TestMultiEndpointBindingRoutingAndRevocation(t *testing.T) {
	store, err := storage.NewSQLiteStorage(filepath.Join(t.TempDir(), "devices.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	create := func(key, platform string) (*model.Device, *rsa.PrivateKey) {
		t.Helper()
		private, err := rsa.GenerateKey(rand.Reader, 2048)
		if err != nil {
			t.Fatal(err)
		}
		der, _ := x509.MarshalPKIXPublicKey(&private.PublicKey)
		d := &model.Device{DeviceKey: key, Platform: platform, Name: platform, PublicKey: string(pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: der}))}
		if err = store.CreateDevice(d); err != nil {
			t.Fatal(err)
		}
		return d, private
	}
	mobile, mobileRSA := create(strings.Repeat("m", 32), "mobile")
	pc, pcRSA := create(strings.Repeat("p", 32), "pc")
	other, _ := create(strings.Repeat("o", 32), "mobile")
	hub := NewHub(store)
	h := NewPushHandler(store, hub)
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.POST("/register", h.HandleRegister)
	router.POST("/push/:device_key", h.HandlePush)
	router.POST("/binding/request", h.HandleBindingRequest)
	router.POST("/binding/verify", h.HandleBindingVerify)
	router.GET("/binding/status", h.HandleBindingStatus)
	router.POST("/binding/remove", h.HandleBindingRemove)
	router.POST("/device/manage", h.HandleManageDevice)
	call := func(method, path, key string, body interface{}, want int) map[string]interface{} {
		t.Helper()
		data, _ := json.Marshal(body)
		r := httptest.NewRequest(method, path, strings.NewReader(string(data)))
		r.Header.Set("Content-Type", "application/json")
		if key != "" {
			r.Header.Set("Authorization", "Bearer "+key)
		}
		w := httptest.NewRecorder()
		router.ServeHTTP(w, r)
		if w.Code != want {
			t.Fatalf("%s: status=%d want=%d body=%s", path, w.Code, want, w.Body.String())
		}
		var result map[string]interface{}
		if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
			t.Fatal(err)
		}
		return result
	}
	call("POST", "/register", "", map[string]string{"device_key": mobile.DeviceKey, "public_key": pc.PublicKey, "platform": "pc"}, 409)
	stored, _ := store.GetDeviceByKey(mobile.DeviceKey)
	if stored.PublicKey != mobile.PublicKey || stored.Platform != "mobile" {
		t.Fatal("PC registration replaced mobile RSA")
	}
	call("POST", "/binding/request", "", map[string]string{"mobile_key": mobile.DeviceKey}, 401)
	challenge := call("POST", "/binding/request", pc.DeviceKey, map[string]string{"mobile_key": mobile.DeviceKey}, 200)
	if challenge["code"] != nil {
		t.Fatal("PC learned verification code from response")
	}
	queued, _ := store.GetUndeliveredMessages(pc.ID)
	if len(queued) != 0 {
		t.Fatal("binding code reached PC")
	}
	queued, _ = store.GetUndeliveredMessages(mobile.ID)
	if len(queued) != 1 {
		t.Fatal("mobile did not receive code")
	}
	payload := decryptAdminTestMessage(t, string(queued[0].EncryptedPayload), mobileRSA)
	code := regexp.MustCompile(`绑定验证码：(\d{6})`).FindStringSubmatch(payload["body"].(string))[1]
	newKey := strings.Repeat("N", 32)
	verify := map[string]string{"request_id": challenge["request_id"].(string), "code": "invalid", "new_device_key": newKey}
	call("POST", "/binding/verify", pc.DeviceKey, verify, 400)
	verify["code"] = code
	bound := call("POST", "/binding/verify", pc.DeviceKey, verify, 200)
	if bound["push_key"] != mobile.DeviceKey {
		t.Fatal("wrong shared ID")
	}
	call("POST", "/binding/verify", pc.DeviceKey, verify, 200) // Lost response retry is idempotent.
	call("POST", "/register", "", map[string]string{"device_key": pc.DeviceKey, "public_key": pc.PublicKey, "platform": "pc"}, 409)
	call("POST", "/push/"+pc.DeviceKey, "", map[string]string{"body": "old address"}, 404)
	status := call("GET", "/binding/status", newKey, nil, 200)
	if status["bound"] != true {
		t.Fatal("missing PC binding")
	}
	pcCurrent, _ := store.GetDeviceByKey(newKey)
	if pcCurrent.Platform != "pc" || pcCurrent.PublicKey != pc.PublicKey {
		t.Fatal("PC lost its isolated RSA key")
	}
	// Remove the code delivery before checking the shared push queues.
	if err = store.MarkMessageDelivered(mobile.ID, queued[0].MessageID); err != nil {
		t.Fatal(err)
	}
	sent := call("POST", "/push/"+mobile.DeviceKey, "", map[string]string{"body": "both", "id": "job"}, 200)
	if sent["target_count"] != float64(2) {
		t.Fatal("default did not target both")
	}
	phoneMessages, _ := store.GetUndeliveredMessages(mobile.ID)
	pcMessages, _ := store.GetUndeliveredMessages(pc.ID)
	if len(phoneMessages) != 1 || len(pcMessages) != 1 || phoneMessages[0].MessageID == pcMessages[0].MessageID {
		t.Fatal("delivery/ACK identities are shared")
	}
	phoneFields := decryptAdminTestMessage(t, string(phoneMessages[0].EncryptedPayload), mobileRSA)
	if phoneFields["body"] != "both" {
		t.Fatal("mobile RSA mismatch")
	}
	pcFields := decryptPCTestMessage(t, string(pcMessages[0].EncryptedPayload), pcRSA)
	if pcFields["body"] != "both" {
		t.Fatal("PC RSA mismatch")
	}
	if err = store.MarkMessageDelivered(mobile.ID, phoneMessages[0].MessageID); err != nil {
		t.Fatal(err)
	}
	if pending, _ := store.GetUndeliveredMessages(pc.ID); len(pending) != 1 {
		t.Fatal("mobile ACK removed PC delivery")
	}
	client := &Client{deviceKey: newKey, deviceID: pc.ID, send: make(chan []byte, 10)}
	hub.sendUndeliveredMessages(client)
	var replay model.WSMessage
	json.Unmarshal(<-client.send, &replay)
	if decryptPCTestMessage(t, replay.Data.(map[string]interface{})["encrypted_content"].(string), pcRSA)["body"] != "both" {
		t.Fatal("PC offline replay used mobile RSA")
	}
	call("POST", "/push/"+mobile.DeviceKey, "", map[string]string{"body": "PC only", "target": "pc"}, 200)
	call("POST", "/push/"+mobile.DeviceKey, "", map[string]string{"body": "alias", "target": "desktop"}, 200)
	call("POST", "/push/"+mobile.DeviceKey, "", map[string]string{"body": "bad", "target": "unknown"}, 400)
	if pending, _ := store.GetUndeliveredMessages(mobile.ID); len(pending) != 0 {
		t.Fatal("PC-only push reached mobile")
	}
	call("POST", "/binding/remove", other.DeviceKey, map[string]int64{"device_id": pc.ID}, 400)
	call("POST", "/binding/remove", mobile.DeviceKey, map[string]int64{"device_id": pc.ID}, 200)
	call("GET", "/binding/status", newKey, nil, 401)
	call("POST", "/register", "", map[string]string{"device_key": newKey, "public_key": pc.PublicKey, "platform": "pc"}, 409)
	call("POST", "/push/"+mobile.DeviceKey, "", map[string]string{"body": "missing PC", "target": "pc"}, 400)
}

func decryptPCTestMessage(t *testing.T, value string, private *rsa.PrivateKey) map[string]interface{} {
	t.Helper()
	data, err := base64.StdEncoding.DecodeString(value)
	if err != nil {
		t.Fatal(err)
	}
	size := int(data[0])<<8 | int(data[1])
	key, err := rsa.DecryptOAEP(sha256.New(), rand.Reader, private, data[2:2+size], nil)
	if err != nil {
		t.Fatal(err)
	}
	block, _ := aes.NewCipher(key)
	gcm, _ := cipher.NewGCM(block)
	plain, err := gcm.Open(nil, data[2+size:14+size], data[14+size:], nil)
	if err != nil {
		t.Fatal(err)
	}
	var fields map[string]interface{}
	if err = json.Unmarshal(plain, &fields); err != nil {
		t.Fatal(err)
	}
	return fields
}
func TestGeneratedDeviceIDsAreAlphanumeric(t *testing.T) {
	for i := 0; i < 100; i++ {
		id, err := messagecrypto.NewCrypto().GenerateDeviceKey()
		if err != nil || !alphanumericID.MatchString(id) {
			t.Fatalf("invalid ID format: %v", err)
		}
	}
}
