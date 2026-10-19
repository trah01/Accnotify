package config

import (
	"os"
	"strconv"
)

// Config holds application configuration
type Config struct {
	// Server settings
	Host              string
	Port              int
	AdminHost         string
	AdminPort         int
	AdminUsername     string
	AdminPassword     string
	AdminCookieSecure bool

	// Database
	DBPath string

	// WebSocket settings
	WSPingInterval int // seconds
	WSPongTimeout  int // seconds

	// Security
	EnableHTTPS bool
	CertFile    string
	KeyFile     string
}

// DefaultConfig returns default configuration
func DefaultConfig() *Config {
	return &Config{
		Host:           "0.0.0.0",
		Port:           8080,
		AdminHost:      "0.0.0.0",
		AdminPort:      8081,
		AdminUsername:  "admin",
		DBPath:         "./data/accnotify.db",
		WSPingInterval: 30,
		WSPongTimeout:  60,
		EnableHTTPS:    false,
	}
}

// LoadFromEnv loads configuration from environment variables
func LoadFromEnv() *Config {
	cfg := DefaultConfig()

	if host := os.Getenv("ACCNOTIFY_HOST"); host != "" {
		cfg.Host = host
	}

	if port := os.Getenv("ACCNOTIFY_PORT"); port != "" {
		if p, err := strconv.Atoi(port); err == nil {
			cfg.Port = p
		}
	}

	if dbPath := os.Getenv("ACCNOTIFY_DB_PATH"); dbPath != "" {
		cfg.DBPath = dbPath
	}
	if host := os.Getenv("ACCNOTIFY_ADMIN_HOST"); host != "" {
		cfg.AdminHost = host
	}
	if port := os.Getenv("ACCNOTIFY_ADMIN_PORT"); port != "" {
		if p, err := strconv.Atoi(port); err == nil && p > 0 && p <= 65535 {
			cfg.AdminPort = p
		}
	}
	if username := os.Getenv("ACCNOTIFY_ADMIN_USERNAME"); username != "" {
		cfg.AdminUsername = username
	}
	cfg.AdminPassword = os.Getenv("ACCNOTIFY_ADMIN_PASSWORD")
	cfg.AdminCookieSecure = os.Getenv("ACCNOTIFY_ADMIN_COOKIE_SECURE") == "true"

	if pingInterval := os.Getenv("ACCNOTIFY_WS_PING_INTERVAL"); pingInterval != "" {
		if p, err := strconv.Atoi(pingInterval); err == nil {
			cfg.WSPingInterval = p
		}
	}

	if os.Getenv("ACCNOTIFY_ENABLE_HTTPS") == "true" {
		cfg.EnableHTTPS = true
		cfg.CertFile = os.Getenv("ACCNOTIFY_CERT_FILE")
		cfg.KeyFile = os.Getenv("ACCNOTIFY_KEY_FILE")
	}

	return cfg
}
