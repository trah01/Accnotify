package model

import (
	"time"
)

// Device represents a registered device
type Device struct {
	PushGroupID int64     `json:"-"`
	PushKey     string    `json:"-"`
	EndpointID  string    `json:"endpoint_id"`
	Platform    string    `json:"platform"`
	ID          int64     `json:"id"`
	DeviceKey   string    `json:"device_key"`
	PublicKey   string    `json:"public_key,omitempty"`
	Name        string    `json:"name,omitempty"`
	CreatedAt   time.Time `json:"created_at"`
	LastSeen    time.Time `json:"last_seen"`
}

// Message represents a notification message
type Message struct {
	ID               int64     `json:"id"`
	DeviceID         int64     `json:"device_id"`
	MessageID        string    `json:"message_id"`
	NotificationID   string    `json:"notification_id,omitempty"`
	DeleteRequested  bool      `json:"delete_requested,omitempty"`
	Title            string    `json:"title,omitempty"`
	Body             string    `json:"body,omitempty"`
	Group            string    `json:"group,omitempty"`
	Icon             string    `json:"icon,omitempty"`
	URL              string    `json:"url,omitempty"`
	Image            string    `json:"image,omitempty"`
	Sound            string    `json:"sound,omitempty"`
	Badge            int       `json:"badge,omitempty"`
	EncryptedPayload []byte    `json:"-"`
	CreatedAt        time.Time `json:"created_at"`
	Delivered        bool      `json:"delivered"`
}

// PushRequest represents an incoming push request
type PushRequest struct {
	Target           string         `json:"target,omitempty"`
	ID               NotificationID `json:"id,omitempty"`
	Delete           PushFlag       `json:"delete,omitempty"`
	Markdown         string         `json:"markdown,omitempty"`
	Encryption       string         `json:"encryption,omitempty"`
	Nonce            string         `json:"nonce,omitempty"`
	Ciphertext       string         `json:"ciphertext,omitempty"`
	Title            string         `json:"title"`
	Body             string         `json:"body"`
	Group            string         `json:"group,omitempty"`
	Icon             string         `json:"icon,omitempty"`
	URL              string         `json:"url,omitempty"`
	Image            string         `json:"image,omitempty"`
	Sound            string         `json:"sound,omitempty"`
	Badge            int            `json:"badge,omitempty"`
	EncryptedContent string         `json:"encrypted_content,omitempty"`
}

// WSMessage represents a WebSocket message
type WSMessage struct {
	Type      string      `json:"type"`
	ID        string      `json:"id"`
	Timestamp int64       `json:"timestamp"`
	Sequence  int64       `json:"sequence,omitempty"`
	Data      interface{} `json:"data,omitempty"`
}

// WSMessageType constants
const (
	WSTypeMessage  = "message"
	WSTypePing     = "ping"
	WSTypePong     = "pong"
	WSTypeAck      = "ack"
	WSTypeRegister = "register"
)

// RegisterRequest represents a device registration request
type RegisterRequest struct {
	Platform  string `json:"platform,omitempty"`
	DeviceKey string `json:"device_key"`
	PublicKey string `json:"public_key"`
	Name      string `json:"name,omitempty"`
}

// PushResponse represents the response after pushing a message
type PushResponse struct {
	TargetCount int    `json:"target_count,omitempty"`
	Success     bool   `json:"success"`
	MessageID   string `json:"message_id,omitempty"`
	Error       string `json:"error,omitempty"`
}

// WebhookRequest represents a generic webhook request
type WebhookRequest struct {
	DeviceKey string `json:"device_key" binding:"required"`
	Title     string `json:"title"`
	Body      string `json:"body" binding:"required"`
	Group     string `json:"group,omitempty"`
	URL       string `json:"url,omitempty"`
	Sound     string `json:"sound,omitempty"`
}

// GitHubWebhook represents a GitHub webhook payload
type GitHubWebhook struct {
	Ref        string `json:"ref"`
	Repository struct {
		Name     string `json:"name"`
		FullName string `json:"full_name"`
		HTMLURL  string `json:"html_url"`
	} `json:"repository"`
	Pusher struct {
		Name  string `json:"name"`
		Email string `json:"email"`
		Login string `json:"login"`
	} `json:"pusher"`
	Sender struct {
		Login string `json:"login"`
	} `json:"sender"`
	HeadCommit struct {
		Message string `json:"message"`
		Author  struct {
			Name string `json:"name"`
		} `json:"author"`
	} `json:"head_commit"`
	Forced bool `json:"forced"`
}

// GitLabWebhook represents a GitLab webhook payload
type GitLabWebhook struct {
	ObjectKind string `json:"object_kind"`
	Ref        string `json:"ref"`
	Project    struct {
		Name   string `json:"name"`
		WebURL string `json:"web_url"`
	} `json:"project"`
	UserUsername string `json:"user_username"`
	UserName     string `json:"user_name"`
	Commit       struct {
		Message string `json:"message"`
		Author  struct {
			Name string `json:"name"`
		} `json:"author"`
	} `json:"commit"`
}

// DockerHubWebhook represents a Docker Hub webhook payload
type DockerHubWebhook struct {
	PushData struct {
		PushedAt int64    `json:"pushed_at"`
		Images   []string `json:"images"`
		Pusher   string   `json:"pusher"`
		Tag      string   `json:"tag"`
	} `json:"push_data"`
	Repository struct {
		Name     string `json:"name"`
		RepoName string `json:"repo_name"`
		RepoURL  string `json:"repo_url"`
	} `json:"repository"`
}

// GiteaWebhook represents a Gitea webhook payload
type GiteaWebhook struct {
	Ref        string `json:"ref"`
	Repository struct {
		Name     string `json:"name"`
		FullName string `json:"full_name"`
		HTMLURL  string `json:"html_url"`
	} `json:"repository"`
	Pusher struct {
		Name  string `json:"name"`
		Login string `json:"login"`
	} `json:"pusher"`
	Sender struct {
		Login string `json:"login"`
	} `json:"sender"`
	HeadCommit struct {
		Message string `json:"message"`
	} `json:"head_commit"`
}
