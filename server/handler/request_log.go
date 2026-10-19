package handler

import (
	"fmt"
	"io"
	"log"
	"net/http"
	"runtime/debug"
	"strings"

	"github.com/gin-gonic/gin"
)

// Recovery must not dump the raw request URL or panic values containing content.
func PrivacyRecovery() gin.HandlerFunc {
	return gin.CustomRecoveryWithWriter(io.Discard, func(c *gin.Context, _ interface{}) {
		log.Printf("Request handler panic:\n%s", debug.Stack())
		c.AbortWithStatus(http.StatusInternalServerError)
	})
}

// PrivacyRequestLogger omits query strings and notification paths containing content.
func PrivacyRequestLogger() gin.HandlerFunc {
	return gin.LoggerWithConfig(gin.LoggerConfig{
		SkipPaths: []string{"/health"},
		Formatter: func(p gin.LogFormatterParams) string {
			path := p.Request.URL.Path
			if path == "/push" || strings.HasPrefix(path, "/push/") {
				path = "/push/[redacted]"
			} else if strings.HasPrefix(path, "/webhook/") {
				path = "/webhook/[redacted]"
			}
			return fmt.Sprintf("[GIN] %s | %3d | %13v | %15s | %-7s %s\n", p.TimeStamp.Format("2006/01/02 - 15:04:05"), p.StatusCode, p.Latency, p.ClientIP, p.Method, path)
		},
	})
}
