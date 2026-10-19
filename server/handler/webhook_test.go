package handler

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strings"
	"testing"

	"github.com/accnotify/server/model"
	"github.com/accnotify/server/storage"
	"github.com/gin-gonic/gin"
)

func TestWebhookKnownPlatforms(t *testing.T) {
	store, err := storage.NewSQLiteStorage(filepath.Join(t.TempDir(), "webhooks.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	device := &model.Device{DeviceKey: "fixture-device", PublicKey: string(pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: der}))}
	if err := store.CreateDevice(device); err != nil {
		t.Fatal(err)
	}
	h := NewWebhookHandler(store, NewHub(store))
	gin.SetMode(gin.TestMode)
	router := gin.New()
	for path, handle := range map[string]gin.HandlerFunc{"": h.HandleGenericWebhook, "/github": h.HandleGitHubWebhook, "/gitlab": h.HandleGitLabWebhook, "/docker": h.HandleDockerHubWebhook, "/gitea": h.HandleGiteaWebhook, "/forgejo": h.HandleForgejoWebhook, "/feishu": h.HandleFeishuWebhook, "/dingtalk": h.HandleDingtalkWebhook, "/wecom": h.HandleWecomWebhook} {
		router.Match([]string{"GET", "POST"}, "/webhook/:device_key"+path, handle)
	}
	for _, tc := range []struct{ name, path, header, event, payload, title, body, target, image string }{
		{"github push", "/github", "X-GitHub-Event", "push", `{"ref":"refs/heads/main","repository":{"full_name":"team/project","html_url":"https://example.com/repo"},"commits":[{"id":"1234567890","message":"first change"},{"id":"abcdef0123","message":"second change"}],"pusher":{"name":"alice"},"compare":"https://example.com/compare"}`, "GitHub · 代码推送", "second change", "https://example.com/compare", ""},
		{"github merged PR", "/github", "X-GitHub-Event", "pull_request", `{"action":"closed","repository":{"full_name":"team/project"},"pull_request":{"number":12,"title":"Fix delivery","merged":true,"head":{"ref":"fix"},"base":{"ref":"main"},"html_url":"https://example.com/pr/12"}}`, "合并请求", "已合并", "https://example.com/pr/12", ""},
		{"github comment generic", "", "X-GitHub-Event", "issue_comment", `{"action":"created","repository":{"full_name":"team/project"},"issue":{"number":5,"title":"Tracking"},"comment":{"body":"please review","html_url":"https://example.com/comment"}}`, "议题评论", "please review", "https://example.com/comment", ""},
		{"github workflow", "/github", "X-GitHub-Event", "workflow_run", `{"workflow_run":{"name":"Docker build","status":"completed","conclusion":"failure","html_url":"https://example.com/run"}}`, "工作流", "状态：失败", "https://example.com/run", ""},
		{"gitea infer push", "/gitea", "", "", `{"ref":"refs/heads/main","repository":{"full_name":"team/project"},"commits":[{"message":"gitea commit"}],"pusher":{"username":"bob"}}`, "Gitea · 代码推送", "gitea commit", "", ""},
		{"forgejo release generic", "", "X-Forgejo-Event", "release", `{"action":"published","repository":{"full_name":"team/project"},"release":{"tag_name":"v1.2","name":"stable","body":"release details","html_url":"https://example.com/release"}}`, "Forgejo · 版本发布", "版本：v1.2", "https://example.com/release", ""},
		{"gitlab actual commits", "/gitlab", "X-Gitlab-Event", "Push Hook", `{"object_kind":"push","ref":"refs/heads/main","project":{"path_with_namespace":"team/project","web_url":"https://example.com/lab"},"commits":[{"id":"111122223333","message":"actual gitlab commit"}],"user_name":"alice","after":"abcdef"}`, "GitLab · 代码推送", "actual gitlab commit", "https://example.com/lab/-/commit/abcdef", ""},
		{"gitlab MR generic", "", "X-Gitlab-Event", "Merge Request Hook", `{"object_kind":"merge_request","project":{"name":"project"},"object_attributes":{"iid":9,"title":"Fix encryption","action":"merge","source_branch":"fix","target_branch":"main","url":"https://example.com/mr/9"}}`, "合并请求", "#9 Fix encryption", "https://example.com/mr/9", ""},
		{"gitlab pipeline", "/gitlab", "", "", `{"object_kind":"pipeline","project":{"web_url":"https://example.com/lab"},"object_attributes":{"id":123,"status":"failed","ref":"main"},"builds":[{"name":"test","status":"failed"}]}`, "流水线", "test：失败", "https://example.com/lab/-/pipelines/123", ""},
		{"docker actual snake_case", "/docker", "", "", `{"push_data":{"tag":"latest","pusher":"alice","pushed_at":1700000000},"repository":{"repo_name":"team/image","repo_url":"https://hub.docker.com/r/team/image"}}`, "Docker Hub · 镜像推送", "镜像：team/image", "https://hub.docker.com/r/team/image", ""},
		{"feishu text generic", "", "", "", `{"msg_type":"text","content":{"text":"服务恢复\n详情如下"}}`, "飞书", "服务恢复\n详情如下", "", ""},
		{"feishu post", "/feishu", "", "", `{"msg_type":"post","content":{"post":{"zh_cn":{"title":"发布成功","content":[[{"tag":"text","text":"查看 "},{"tag":"a","text":"详情","href":"https://example.com/post"}]]}}}}`, "发布成功", "查看 详情", "https://example.com/post", ""},
		{"feishu card", "/feishu", "", "", `{"msg_type":"interactive","card":{"header":{"title":{"content":"构建结果"}},"elements":[{"tag":"div","text":{"tag":"lark_md","content":"构建完成"}},{"tag":"action","actions":[{"tag":"button","text":{"content":"查看"},"url":"https://example.com/build"}]}]}}`, "构建结果", "构建完成", "https://example.com/build", ""},
		{"dingtalk markdown generic", "", "", "", `{"msgtype":"markdown","markdown":{"title":"钉钉告警","text":"## CPU 告警\n使用率 95%"}}`, "钉钉告警", "CPU 告警", "", ""},
		{"dingtalk link", "/dingtalk", "", "", `{"msgtype":"link","link":{"title":"部署通知","text":"部署完成","messageUrl":"https://example.com/deploy","picUrl":"https://example.com/image.png"}}`, "部署通知", "部署完成", "https://example.com/deploy", "https://example.com/image.png"},
		{"wecom text", "/wecom", "", "", `{"msgtype":"text","text":{"content":"企微通知"}}`, "企业微信", "企微通知", "", ""},
		{"wecom news generic", "", "", "", `{"msgtype":"news","news":{"articles":[{"title":"设备告警","description":"磁盘不足","url":"https://example.com/alert","picurl":"https://example.com/pic.png"}]}}`, "设备告警", "磁盘不足", "https://example.com/alert", "https://example.com/pic.png"},
		{"wecom template", "/wecom", "", "", `{"msgtype":"template_card","template_card":{"main_title":{"title":"运维通知","desc":"已恢复"},"horizontal_content_list":[{"keyname":"实例","value":"server-1"}],"card_action":{"type":1,"url":"https://example.com/server"}}}`, "运维通知", "实例：server-1", "https://example.com/server", ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			r := httptest.NewRequest("POST", "/webhook/fixture-device"+tc.path, strings.NewReader(tc.payload))
			r.Header.Set("Content-Type", "application/json")
			if tc.header != "" {
				r.Header.Set(tc.header, tc.event)
			}
			w := httptest.NewRecorder()
			router.ServeHTTP(w, r)
			if w.Code != 200 {
				t.Fatalf("webhook rejected: %d %s", w.Code, w.Body.String())
			}
			var response model.PushResponse
			if err := json.Unmarshal(w.Body.Bytes(), &response); err != nil {
				t.Fatal(err)
			}
			pending, err := store.GetUndeliveredMessages(device.ID)
			if err != nil {
				t.Fatal(err)
			}
			var msg *model.Message
			for _, candidate := range pending {
				if candidate.MessageID == response.MessageID {
					msg = candidate
				}
			}
			if msg == nil || !strings.Contains(msg.Title, tc.title) || !strings.Contains(strings.Split(msg.Body, "--- 完整数据 ---")[0], tc.body) || msg.URL != tc.target || msg.Image != tc.image {
				t.Fatalf("unexpected formatted webhook: %+v", msg)
			}
			if !strings.Contains(msg.Body, "--- 完整数据 ---") {
				t.Fatal("original payload missing")
			}
			fields := decryptAdminTestMessage(t, string(msg.EncryptedPayload), key)
			if fields["url"] != tc.target || fields["body"] != msg.Body || fields["title"] != msg.Title {
				t.Fatal("encrypted notification lost parsed fields")
			}
		})
	}

	// Check the delivered fields, not just HTTP acceptance, for both entry modes.
	for _, tc := range []struct {
		method, path, contentType, body, id, expectedBody string
		badge, status                                     int
	}{
		{"POST", "/forgejo?id=repo-status&group=work&markdown=updated&icon=icon&sound=bell&badge=3", "application/json", `{"id":123,"ref":"refs/heads/main","repository":{"full_name":"team/project"}}`, "repo-status", "updated", 3, 200},
		{"POST", "?id=query-id&group=work&icon=icon&sound=bell&badge=3", "application/json", `{"id":"body-id","body":"updated"}`, "query-id", "updated", 3, 200},
		{"POST", "", "application/x-www-form-urlencoded", "id=form-id&markdown=updated&group=work&icon=icon&sound=bell&badge=3", "form-id", "updated", 3, 200},
		{"GET", "?id=get-id&body=updated&group=work&icon=icon&sound=bell&badge=3", "", "", "get-id", "updated", 3, 200},
		{"POST", "/forgejo?id=repo-status&delete=1", "application/json", `{"repository":{"full_name":"team/project"}}`, "repo-status", "", 0, 200},
		{"POST", "/forgejo?delete=1", "application/json", `{}`, "", "", 0, 400},
		{"POST", "/forgejo?badge=wrong", "application/json", `{}`, "", "", 0, 400},
		{"POST", "/forgejo?id=" + strings.Repeat("x", 257), "application/json", `{}`, "", "", 0, 400},
	} {
		r := httptest.NewRequest(tc.method, "/webhook/fixture-device"+tc.path, strings.NewReader(tc.body))
		r.Header.Set("Content-Type", tc.contentType)
		w := httptest.NewRecorder()
		router.ServeHTTP(w, r)
		if w.Code != tc.status {
			t.Fatalf("%s %s: %d %s", tc.method, tc.path, w.Code, w.Body.String())
		}
		if tc.status != 200 {
			continue
		}
		var response model.PushResponse
		if err := json.Unmarshal(w.Body.Bytes(), &response); err != nil {
			t.Fatal(err)
		}
		pending, err := store.GetUndeliveredMessages(device.ID)
		if err != nil {
			t.Fatal(err)
		}
		found := false
		for _, msg := range pending {
			if msg.MessageID != response.MessageID {
				continue
			}
			found = true
			fields := decryptAdminTestMessage(t, string(msg.EncryptedPayload), key)
			if fields["id"] != tc.id {
				t.Fatalf("missing message id: %+v", fields)
			}
			if strings.Contains(tc.path, "delete=1") {
				if fields["delete"] != "1" {
					t.Fatalf("missing deletion: %+v", fields)
				}
			} else if fields["body"] != tc.expectedBody || fields["group"] != "work" || fields["icon"] != "icon" || fields["sound"] != "bell" || msg.Badge != tc.badge {
				t.Fatalf("lost push fields: %+v / %+v", msg, fields)
			}
		}
		if !found {
			t.Fatal("accepted webhook was not queued")
		}
	}
	for _, tc := range []struct {
		path, body, contentType string
		status                  int
	}{
		{"/github", `{"ref":`, "application/json", 400},
		{"/docker", `null`, "application/json", 400},
		{"", `{broken`, "application/json", 400},
		{"", ``, "text/plain", 400},
		{"", `plain message`, "text/plain", 200},
		{"", `[1,{"message":"retained"}]`, "application/json", 200},
		{"/github", "payload=" + url.QueryEscape(`{"ref":"refs/heads/main","commits":[{"message":"form commit"}]}`), "application/x-www-form-urlencoded", 200},
	} {
		r := httptest.NewRequest("POST", "/webhook/fixture-device"+tc.path, strings.NewReader(tc.body))
		r.Header.Set("Content-Type", tc.contentType)
		w := httptest.NewRecorder()
		router.ServeHTTP(w, r)
		if w.Code != tc.status {
			t.Fatalf("input %q: status %d, want %d", tc.body, w.Code, tc.status)
		}
	}
}
