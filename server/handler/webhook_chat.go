package handler

import (
	"golang.org/x/net/html"
	"strings"
)

func formatChatWebhook(provider string, data map[string]interface{}) webhookMessage {
	result := webhookMessage{title: provider, image: text(data, "image"), url: text(data, "url")}
	kind := text(data, "msgtype", "msg_type")
	if provider == "飞书" {
		switch kind {
		case "text":
			result.body = text(data, "content.text", "text")
		case "post":
			post := object(field(data, "content.post"))
			// Prefer Chinese, then English; direct post objects are also valid.
			for _, locale := range []string{"zh_cn", "zh_CN", "en_us"} {
				if localized := object(post[locale]); localized != nil {
					post = localized
					break
				}
			}
			if title := text(post, "title"); title != "" {
				result.title = title
			}
			for _, row := range array(post["content"]) {
				var parts []string
				for _, item := range array(row) {
					entry := object(item)
					tag := text(entry, "tag")
					if tag == "a" {
						link := text(entry, "href")
						parts = append(parts, text(entry, "text")+" ("+link+")")
						if result.url == "" {
							result.url = link
						}
					} else {
						if value, ok := entry["text"].(string); ok {
							parts = append(parts, value)
						} else {
							parts = append(parts, text(entry, "user_name"))
						}
					}
				}
				result.body = lines(result.body, strings.Join(parts, ""))
			}
		case "interactive":
			card := object(data["card"])
			if title := text(card, "header.title.content"); title != "" {
				result.title = title
			}
			chatCardContent(card, &result)
			chatCardContent(object(card["body"]), &result)
		case "image":
			result.body = "图片消息（飞书图片标识无法直接下载）"
		}
	} else if provider == "钉钉" {
		switch kind {
		case "text":
			result.body = text(data, "text.content")
		case "markdown":
			result.title = text(data, "markdown.title")
			result.body = text(data, "markdown.text")
		case "link":
			result.title = text(data, "link.title")
			result.body = text(data, "link.text")
			result.url = text(data, "link.messageUrl")
			result.image = text(data, "link.picUrl")
		case "actionCard":
			result.title = text(data, "actionCard.title")
			result.body = text(data, "actionCard.text")
			result.url = text(data, "actionCard.singleURL")
			for _, item := range array(field(data, "actionCard.btns")) {
				entry := object(item)
				link := text(entry, "actionURL")
				result.body = lines(result.body, text(entry, "title"), link)
				if result.url == "" {
					result.url = link
				}
			}
		case "feedCard":
			for _, item := range array(field(data, "feedCard.links")) {
				entry := object(item)
				result.body = lines(result.body, text(entry, "title"), text(entry, "messageURL"))
				if result.url == "" {
					result.url = text(entry, "messageURL")
					result.image = text(entry, "picURL")
				}
			}
		}
	} else {
		switch kind {
		case "text":
			result.body = text(data, "text.content")
		case "markdown":
			result.body = text(data, "markdown.content")
		case "textcard":
			result.title = text(data, "textcard.title")
			result.body = webhookHTMLText(text(data, "textcard.description"))
			result.url = text(data, "textcard.url")
		case "news":
			for i, item := range array(field(data, "news.articles")) {
				article := object(item)
				if i == 0 {
					result.title = text(article, "title")
					result.url = text(article, "url")
					result.image = text(article, "picurl")
				}
				result.body = lines(result.body, text(article, "title"), text(article, "description"), text(article, "url"))
			}
		case "template_card":
			card := object(data["template_card"])
			result.title = text(card, "main_title.title")
			result.body = lines(text(card, "main_title.desc"), text(card, "sub_title_text"), text(card, "quote_area.title"), text(card, "quote_area.quote_text"))
			result.url = text(card, "card_action.url", "quote_area.url")
			for _, item := range array(card["horizontal_content_list"]) {
				entry := object(item)
				result.body = lines(result.body, line(text(entry, "keyname"), text(entry, "value", "url")))
			}
			for _, item := range array(card["jump_list"]) {
				entry := object(item)
				link := text(entry, "url")
				result.body = lines(result.body, text(entry, "title"), link)
				if result.url == "" {
					result.url = link
				}
			}
		}
	}
	if result.body == "" {
		fallback := formatGenericWebhook(data)
		result.body = fallback.body
		if result.url == "" {
			result.url = fallback.url
		}
	}
	if result.title == "" {
		result.title = provider
	}
	return result
}

func webhookHTMLText(value string) string {
	document, err := html.Parse(strings.NewReader(value))
	if err != nil {
		return value
	}
	var builder strings.Builder
	var walk func(*html.Node)
	walk = func(node *html.Node) {
		block := node.Type == html.ElementNode && (node.Data == "div" || node.Data == "p" || node.Data == "br" || node.Data == "li")
		if block {
			builder.WriteByte('\n')
		}
		if node.Type == html.TextNode {
			builder.WriteString(node.Data)
		}
		for child := node.FirstChild; child != nil; child = child.NextSibling {
			walk(child)
		}
		if block && node.Data != "br" {
			builder.WriteByte('\n')
		}
	}
	walk(document)
	var result []string
	for _, line := range strings.Split(builder.String(), "\n") {
		if clean := strings.TrimSpace(line); clean != "" {
			result = append(result, clean)
		}
	}
	return strings.Join(result, "\n")
}

func chatCardContent(card map[string]interface{}, result *webhookMessage) {
	for _, item := range array(card["elements"]) {
		entry := object(item)
		result.body = lines(result.body, text(entry, "content", "text.content"))
		if link := text(entry, "url", "multi_url.url"); link != "" {
			result.body = lines(result.body, link)
			if result.url == "" {
				result.url = link
			}
		}
		for _, value := range array(entry["fields"]) {
			result.body = lines(result.body, text(object(value), "text.content", "content"))
		}
		for _, value := range array(entry["actions"]) {
			action := object(value)
			link := text(action, "url", "multi_url.url", "multi_url.android_url")
			result.body = lines(result.body, text(action, "text.content"), link)
			if result.url == "" {
				result.url = link
			}
		}
		// Containers in card schema 2.0 can nest ordinary elements.
		for _, column := range array(entry["columns"]) {
			chatCardContent(object(column), result)
		}
		if len(array(entry["elements"])) > 0 {
			chatCardContent(entry, result)
		}
	}
}
