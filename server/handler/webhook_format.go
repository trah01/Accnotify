package handler

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
)

type webhookMessage struct{ title, body, url, image string }

func field(data map[string]interface{}, path string) interface{} {
	var value interface{} = data
	for _, key := range strings.Split(path, ".") {
		object, ok := value.(map[string]interface{})
		if !ok {
			return nil
		}
		value = object[key]
	}
	return value
}
func text(data map[string]interface{}, paths ...string) string {
	for _, path := range paths {
		switch v := field(data, path).(type) {
		case string:
			if strings.TrimSpace(v) != "" {
				return strings.TrimSpace(v)
			}
		case json.Number:
			return v.String()
		}
	}
	return ""
}
func object(value interface{}) map[string]interface{} {
	result, _ := value.(map[string]interface{})
	return result
}
func array(value interface{}) []interface{} { result, _ := value.([]interface{}); return result }
func truth(data map[string]interface{}, path string) bool {
	value, _ := field(data, path).(bool)
	return value
}
func line(label, value string) string {
	if value == "" {
		return ""
	}
	return label + "：" + value
}
func lines(values ...string) string {
	var result []string
	for _, value := range values {
		if value != "" {
			result = append(result, value)
		}
	}
	return strings.Join(result, "\n")
}
func firstLine(value string) string { return strings.Split(strings.TrimSpace(value), "\n")[0] }
func shortID(id string) string {
	if len(id) > 8 {
		return id[:8]
	}
	return id
}
func actionName(action string) string {
	names := map[string]string{"opened": "已创建", "open": "已创建", "closed": "已关闭", "close": "已关闭", "reopened": "重新打开", "reopen": "重新打开", "edited": "已编辑", "updated": "已更新", "update": "已更新", "created": "已创建", "published": "已发布", "released": "已发布", "deleted": "已删除", "merged": "已合并", "merge": "已合并", "approved": "已批准", "submitted": "已提交", "synchronize": "更新提交", "synchronized": "更新提交", "started": "已关注", "completed": "已完成", "labeled": "添加标签", "unlabeled": "移除标签", "assigned": "分配负责人", "unassigned": "取消负责人"}
	if value := names[action]; value != "" {
		return value
	}
	return action
}
func statusName(status string) string {
	names := map[string]string{"success": "成功", "failure": "失败", "failed": "失败", "firing": "告警中", "resolved": "已恢复", "cancelled": "已取消", "canceled": "已取消", "running": "运行中", "pending": "等待中", "queued": "排队中", "in_progress": "运行中", "completed": "已完成", "skipped": "已跳过", "timed_out": "超时", "neutral": "中性"}
	if value := names[status]; value != "" {
		return value
	}
	return status
}

func webhookEvent(headers http.Header, data map[string]interface{}) string {
	for _, name := range []string{"X-GitHub-Event", "X-Forgejo-Event", "X-Gitea-Event"} {
		if event := headers.Get(name); event != "" {
			return event
		}
	}
	if event := text(data, "object_kind", "event_type"); event != "" {
		return event
	}
	return strings.ReplaceAll(strings.ToLower(strings.TrimSuffix(headers.Get("X-Gitlab-Event"), " Hook")), " ", "_")
}
func detectWebhookProvider(headers http.Header, data map[string]interface{}) string {
	for _, item := range []struct{ header, name string }{{"X-GitHub-Event", "GitHub"}, {"X-Forgejo-Event", "Forgejo"}, {"X-Gitea-Event", "Gitea"}, {"X-Gitlab-Event", "GitLab"}} {
		if headers.Get(item.header) != "" {
			return item.name
		}
	}
	if field(data, "object_kind") != nil && field(data, "project") != nil {
		return "GitLab"
	}
	if field(data, "push_data") != nil && field(data, "repository") != nil {
		return "Docker Hub"
	}
	if text(data, "msg_type") != "" {
		return "飞书"
	}
	if kind := text(data, "msgtype"); kind != "" {
		// DingTalk and WeCom text messages have the same wire format.
		// Use the generic title unless the caller selects a dedicated route.
		if kind == "text" {
			return ""
		}
		if field(data, "markdown.content") != nil || kind == "news" || kind == "textcard" || kind == "template_card" {
			return "企业微信"
		}
		return "钉钉"
	}
	if field(data, "repository") != nil && (field(data, "ref") != nil || field(data, "pull_request") != nil || field(data, "issue") != nil || field(data, "release") != nil || field(data, "workflow_run") != nil) {
		return "GitHub"
	}
	return ""
}
func formatWebhook(provider, event string, data map[string]interface{}) webhookMessage {
	switch provider {
	case "GitHub", "Gitea", "Forgejo":
		return formatGitWebhook(provider, event, data)
	case "GitLab":
		return formatGitLabEvent(event, data)
	case "Docker Hub":
		repo := text(data, "repository.repo_name", "repository.name", "repository.repo_url")
		title := "Docker Hub · 镜像推送"
		if repo != "" {
			title += " · " + repo
		}
		return webhookMessage{title: title, body: lines(line("镜像", repo), line("标签", text(data, "push_data.tag")), line("推送者", text(data, "push_data.pusher"))), url: text(data, "repository.repo_url"), image: text(data, "repository.logo_url")}
	case "飞书", "钉钉", "企业微信":
		return formatChatWebhook(provider, data)
	default:
		return formatGenericWebhook(data)
	}
}
func formatGenericWebhook(data map[string]interface{}) webhookMessage {
	title := text(data, "title", "Title", "TITLE", "subject", "Subject", "name", "event", "event_type", "eventType", "action", "type", "alert_name", "alertName")
	if title == "" {
		title = "Webhook"
	} else {
		title = "Webhook: " + title
	}
	body := text(data, "body", "Body", "BODY", "message", "Message", "content", "Content", "text", "Text", "description", "summary", "details")
	if body == "" {
		for _, key := range []string{"text", "content", "data", "payload", "details"} {
			if nested := object(data[key]); nested != nil {
				body = text(nested, "content", "text", "body", "message", "description")
				if body != "" {
					break
				}
			}
		}
	}
	return webhookMessage{title: title, body: body, url: text(data, "url", "link", "messageUrl", "html_url"), image: text(data, "image", "picUrl", "picurl")}
}

func commitLines(data map[string]interface{}) string {
	commits := array(data["commits"])
	if len(commits) == 0 && object(data["head_commit"]) != nil {
		commits = []interface{}{data["head_commit"]}
	}
	if len(commits) == 0 {
		return ""
	}
	result := []string{fmt.Sprintf("提交数量：%d", len(commits))}
	for i, value := range commits {
		if i == 8 {
			result = append(result, fmt.Sprintf("另有 %d 条提交", len(commits)-i))
			break
		}
		commit := object(value)
		result = append(result, strings.TrimSpace(shortID(text(commit, "id", "sha"))+" "+firstLine(text(commit, "message", "title"))))
	}
	return lines(result...)
}
