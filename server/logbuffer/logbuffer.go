// Package logbuffer mirrors application output into a bounded, in-memory log.
package logbuffer

import (
	"bytes"
	"io"
	"strconv"
	"sync"
	"time"
)

type Buffer struct {
	mu       sync.Mutex
	output   io.Writer
	data     []byte
	capacity int
	end      int64
	instance string
}

func New(output io.Writer, capacity int) *Buffer {
	if capacity < 1 {
		capacity = 1024 * 1024
	}
	return &Buffer{output: output, capacity: capacity, instance: strconv.FormatInt(time.Now().UnixNano(), 10)}
}

func (b *Buffer) Instance() string { return b.instance }

// Write forwards the exact same bytes to Docker's stdout and the admin log.
func (b *Buffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	n, err := b.output.Write(p)
	b.data = append(b.data, p[:n]...)
	b.end += int64(n)
	if len(b.data) > b.capacity {
		cut := len(b.data) - b.capacity
		if newline := bytes.IndexByte(b.data[cut:], '\n'); newline >= 0 {
			cut += newline + 1
		}
		b.data = append([]byte(nil), b.data[cut:]...)
	}
	return n, err
}

func (b *Buffer) ReadSince(cursor int64) (text string, next int64, truncated bool) {
	b.mu.Lock()
	defer b.mu.Unlock()
	start := b.end - int64(len(b.data))
	if cursor < start || cursor > b.end {
		truncated = cursor >= 0
		cursor = start
	}
	return string(b.data[cursor-start:]), b.end, truncated
}
