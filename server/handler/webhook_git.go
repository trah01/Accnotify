package handler

import (
	"fmt"
	"strings"
)

func formatGitWebhook(provider, event string, data map[string]interface{}) webhookMessage {
	if event == "" {
		switch {
		case field(data, "pull_request") != nil:
			event = "pull_request"
		case field(data, "issue") != nil && field(data, "comment") != nil:
			event = "issue_comment"
		case field(data, "issue") != nil:
			event = "issues"
		case field(data, "release") != nil:
			event = "release"
		case field(data, "workflow_run") != nil:
			event = "workflow_run"
		case field(data, "commits") != nil || field(data, "ref") != nil:
			event = "push"
		}
	}
	repo := text(data, "repository.full_name", "repository.name")
	actor := text(data, "sender.login", "sender.username", "sender.full_name", "pusher.name", "pusher.username")
	result := webhookMessage{title: provider, url: text(data, "repository.html_url"), image: text(data, "image")}
	base := line("仓库", repo)
	action := actionName(text(data, "action"))
	summary := ""
	switch event {
	case "push":
		ref := text(data, "ref")
		label := "分支"
		if strings.HasPrefix(ref, "refs/tags/") {
			label = "标签"
		}
		ref = strings.TrimPrefix(strings.TrimPrefix(ref, "refs/heads/"), "refs/tags/")
		summary = "代码推送"
		flag := ""
		if truth(data, "deleted") {
			summary = "删除" + label
			flag = "删除" + label
		} else if truth(data, "created") {
			flag = "新建" + label
		}
		if truth(data, "forced") {
			flag = lines(flag, "强制推送")
		}
		result.body = lines(base, line(label, ref), flag, commitLines(data), line("推送者", text(data, "pusher.name", "pusher.username", "sender.login", "sender.username")))
		if target := text(data, "compare", "compare_url", "head_commit.url"); target != "" {
			result.url = target
		}
	case "pull_request", "pull_request_review", "pull_request_review_comment":
		pr := object(data["pull_request"])
		if text(data, "action") == "closed" && truth(pr, "merged") {
			action = "已合并"
		}
		summary = "合并请求"
		if event != "pull_request" {
			summary = "合并请求评审"
		}
		result.body = lines(base, line("合并请求", numberedTitle(pr)), line("状态", action),
			line("来源分支", text(pr, "head.ref")), line("目标分支", text(pr, "base.ref")),
			line("评审结果", text(data, "review.state")), text(data, "comment.body", "review.body", "pull_request.body"), line("操作人", actor))
		result.url = text(data, "comment.html_url", "review.html_url", "pull_request.html_url", "pull_request.url")
	case "issues", "issue", "issue_comment":
		issue := object(data["issue"])
		summary = "议题"
		if event == "issue_comment" {
			summary = "议题评论"
		}
		if field(issue, "pull_request") != nil {
			summary = "合并请求评论"
		}
		result.body = lines(base, line("主题", numberedTitle(issue)), line("状态", action), text(data, "comment.body", "issue.body"), line("操作人", actor))
		result.url = text(data, "comment.html_url", "issue.html_url", "issue.url")
	case "release":
		summary = "版本发布"
		result.body = lines(base, line("版本", text(data, "release.tag_name")), line("名称", text(data, "release.name")), line("状态", action), text(data, "release.body"), line("发布者", actor))
		result.url = text(data, "release.html_url", "release.url")
	case "workflow_run", "workflow_job", "check_run":
		key := event
		entry := object(data[key])
		summary = "工作流"
		state := text(entry, "conclusion", "status")
		result.body = lines(base, line("任务", text(entry, "name")), line("状态", statusName(state)), line("分支", text(entry, "head_branch")), line("提交", shortID(text(entry, "head_sha"))), line("操作人", actor))
		result.url = text(entry, "html_url", "details_url")
	case "create", "delete":
		summary = "创建引用"
		if event == "delete" {
			summary = "删除引用"
		}
		result.body = lines(base, line("操作", summary), line("类型", text(data, "ref_type")), line("名称", text(data, "ref")), line("操作人", actor))
	case "star", "watch":
		summary = "仓库关注"
		result.body = lines(base, line("操作", action), line("Star 数量", text(data, "repository.stargazers_count")), line("操作人", actor))
	case "fork":
		summary = "仓库派生"
		result.body = lines(base, line("新仓库", text(data, "forkee.full_name")), line("操作人", actor))
		result.url = text(data, "forkee.html_url")
	case "ping":
		summary = "连接测试"
		result.body = lines(base, "Webhook 连接测试", text(data, "zen"))
	default:
		summary = event
		// Unknown events keep generic fields and the full JSON, rather than
		// falsely treating them as a push or dropping the event details.
		result.body = lines(base, line("事件", event), line("操作", action), formatGenericWebhook(data).body, line("操作人", actor))
	}
	if summary != "" {
		result.title = provider + " · " + summary
	}
	if repo != "" {
		result.title += " · " + repo
	}
	if result.url == "" {
		result.url = text(data, "repository.html_url")
	}
	return result
}

func numberedTitle(data map[string]interface{}) string {
	name := text(data, "title")
	number := text(data, "number", "iid")
	if number != "" {
		return strings.TrimSpace("#" + number + " " + name)
	}
	return name
}

func formatGitLabEvent(event string, data map[string]interface{}) webhookMessage {
	if event == "" {
		event = text(data, "object_kind", "event_type")
	}
	repo := text(data, "project.path_with_namespace", "project.name", "repository.name", "project_name")
	actor := text(data, "user_name", "user.name", "user.username")
	attributes := object(data["object_attributes"])
	result := webhookMessage{title: "GitLab", url: text(data, "project.web_url", "repository.homepage")}
	base := line("项目", repo)
	summary := event
	switch event {
	case "push", "tag_push":
		summary = "代码推送"
		label := "分支"
		if event == "tag_push" {
			summary = "标签推送"
			label = "标签"
		}
		ref := strings.TrimPrefix(strings.TrimPrefix(text(data, "ref"), "refs/heads/"), "refs/tags/")
		deleted := text(data, "after") == strings.Repeat("0", 40)
		flag := ""
		if deleted {
			flag = "删除" + label
			summary = flag
		}
		result.body = lines(base, line(label, ref), flag, commitLines(data), line("推送者", actor))
		if head := text(data, "checkout_sha", "after"); head != "" && !deleted && result.url != "" {
			result.url = strings.TrimRight(result.url, "/") + "/-/commit/" + head
		}
	case "merge_request":
		summary = "合并请求"
		result.body = lines(base, line("合并请求", numberedTitle(attributes)), line("状态", actionName(text(attributes, "action", "state"))), line("来源分支", text(attributes, "source_branch")), line("目标分支", text(attributes, "target_branch")), text(attributes, "description"), line("操作人", actor))
		result.url = text(attributes, "url")
	case "issue":
		summary = "议题"
		result.body = lines(base, line("议题", numberedTitle(attributes)), line("状态", actionName(text(attributes, "action", "state"))), text(attributes, "description"), line("操作人", actor))
		result.url = text(attributes, "url")
	case "note":
		summary = "评论"
		target := object(data["merge_request"])
		if target == nil {
			target = object(data["issue"])
		}
		result.body = lines(base, line("主题", numberedTitle(target)), line("类型", text(attributes, "noteable_type")), text(attributes, "note"), line("评论者", actor))
		result.url = text(attributes, "url")
	case "pipeline":
		summary = "流水线"
		result.body = lines(base, line("流水线", text(attributes, "id")), line("状态", statusName(text(attributes, "status"))), line("分支", text(attributes, "ref")), line("提交", firstLine(text(data, "commit.message", "commit.title"))), line("操作人", actor))
		for _, item := range array(data["builds"]) {
			job := object(item)
			result.body = lines(result.body, line(text(job, "name"), statusName(text(job, "status"))))
		}
		result.url = text(attributes, "url")
		if result.url == "" && text(attributes, "id") != "" && text(data, "project.web_url") != "" {
			result.url = text(data, "project.web_url") + "/-/pipelines/" + text(attributes, "id")
		}
	case "build", "job":
		summary = "构建任务"
		result.body = lines(base, line("任务", text(data, "build_name")), line("阶段", text(data, "build_stage")), line("状态", statusName(text(data, "build_status"))), line("分支", text(data, "ref")), line("操作人", actor))
		if id := text(data, "build_id"); id != "" && result.url != "" {
			result.url += "/-/jobs/" + id
		}
	case "release":
		summary = "版本发布"
		result.body = lines(base, line("版本", text(data, "tag")), line("名称", text(data, "name")), line("状态", actionName(text(data, "action"))), text(data, "description"))
		result.url = text(data, "url")
	case "wiki_page":
		summary = "文档更新"
		result.body = lines(base, line("文档", text(attributes, "title")), line("状态", actionName(text(attributes, "action"))), text(attributes, "message"), line("操作人", actor))
		result.url = text(attributes, "url")
	default:
		result.body = lines(base, line("事件", event), formatGenericWebhook(data).body, line("操作人", actor))
	}
	if summary != "" {
		result.title = fmt.Sprintf("GitLab · %s", summary)
	}
	if repo != "" {
		result.title += " · " + repo
	}
	if result.url == "" {
		result.url = text(data, "project.web_url", "repository.homepage")
	}
	return result
}
