// Send a JSON notification from stdin using sender-side AES-256-GCM encryption.
// The shared key stays in the sender's environment and the receiving app.
package main

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

func main() {
	if err := send(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func send() error {
	key, err := hex.DecodeString(strings.TrimSpace(os.Getenv("ACCNOTIFY_ENCRYPTION_KEY")))
	if err != nil || len(key) != 32 {
		return fmt.Errorf("ACCNOTIFY_ENCRYPTION_KEY 必须是 App 中配置的 64 位十六进制密钥")
	}
	endpoint := os.Getenv("ACCNOTIFY_PUSH_URL")
	parsed, err := url.Parse(endpoint)
	if err != nil || parsed.Host == "" || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.RawQuery != "" || parsed.Fragment != "" {
		return fmt.Errorf("请将 ACCNOTIFY_PUSH_URL 设置为 http(s)://服务器/push/设备密钥，不附带查询参数")
	}
	plaintext, err := io.ReadAll(io.LimitReader(os.Stdin, 4*1024*1024+1))
	if err != nil || len(plaintext)+16 > 4*1024*1024 {
		return fmt.Errorf("读取消息失败或内容超过 4 MB")
	}
	var fields map[string]json.RawMessage
	if json.Unmarshal(plaintext, &fields) != nil {
		return fmt.Errorf("请输入 JSON 消息对象")
	}
	var deleteValue interface{}
	_ = json.Unmarshal(fields["delete"], &deleteValue)
	if deleteValue == "1" || deleteValue == float64(1) || deleteValue == true {
		var id string
		if json.Unmarshal(fields["id"], &id) != nil || id == "" {
			return fmt.Errorf("删除消息必须提供字符串 id")
		}
	} else {
		var body, markdown string
		_ = json.Unmarshal(fields["markdown"], &markdown)
		if markdown != "" {
			body = markdown
		} else {
			_ = json.Unmarshal(fields["body"], &body)
		}
		if strings.TrimSpace(body) == "" {
			return fmt.Errorf("请输入包含非空 body 或 markdown 字符串的 JSON 消息")
		}
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return fmt.Errorf("随机数生成失败")
	}
	payload, err := json.Marshal(map[string]string{
		"encryption": "aes-256-gcm",
		"nonce":      base64.StdEncoding.EncodeToString(nonce),
		"ciphertext": base64.StdEncoding.EncodeToString(gcm.Seal(nil, nonce, plaintext, nil)),
	})
	if err != nil {
		return err
	}
	request, err := http.NewRequest(http.MethodPost, endpoint, bytes.NewReader(payload))
	if err != nil {
		return fmt.Errorf("推送地址无效")
	}
	request.Header.Set("Content-Type", "application/json")
	client := &http.Client{Timeout: 30 * time.Second, CheckRedirect: func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse }}
	response, err := client.Do(request)
	if err != nil {
		return fmt.Errorf("推送请求失败，请检查服务器地址和网络")
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("推送失败，HTTP 状态码：%d", response.StatusCode)
	}
	var result struct {
		Success   bool   `json:"success"`
		MessageID string `json:"message_id"`
	}
	if json.NewDecoder(response.Body).Decode(&result) != nil || !result.Success {
		return fmt.Errorf("服务器响应异常")
	}
	fmt.Println("加密消息已提交，消息 ID：" + result.MessageID)
	return nil
}
