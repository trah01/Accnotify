package handler

import (
	"bytes"
	"log"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

func TestRequestLogsDoNotRetainNotificationContent(t *testing.T) {
	var output bytes.Buffer
	oldWriter, oldLogWriter := gin.DefaultWriter, log.Writer()
	gin.DefaultWriter = &output
	log.SetOutput(&output)
	defer func() { gin.DefaultWriter = oldWriter; log.SetOutput(oldLogWriter) }()
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(PrivacyRequestLogger(), PrivacyRecovery())
	router.GET("/push/:key/*params", func(c *gin.Context) {
		if c.Query("panic") != "" {
			panic("secret-message")
		}
		c.Status(200)
	})
	for _, suffix := range []string{"?body=secret-query", "?panic=yes"} {
		response := httptest.NewRecorder()
		router.ServeHTTP(response, httptest.NewRequest("GET", "/push/secret-key/secret-message"+suffix, nil))
	}
	logs := output.String()
	for _, secret := range []string{"secret-key", "secret-message", "secret-query", "?panic"} {
		if strings.Contains(logs, secret) {
			t.Fatalf("request content exposed in logs: %s", secret)
		}
	}
	if !strings.Contains(logs, "/push/[redacted]") || !strings.Contains(logs, "500") {
		t.Fatal("request status logs missing")
	}
}
