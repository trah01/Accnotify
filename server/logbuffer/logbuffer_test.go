package logbuffer

import (
	"bytes"
	"testing"
)

func TestLogBufferMirrorsOutputAndCursor(t *testing.T) {
	var output bytes.Buffer
	b := New(&output, 24)
	b.Write([]byte("first\n"))
	text, cursor, truncated := b.ReadSince(-1)
	if text != "first\n" || truncated {
		t.Fatal("incorrect initial read")
	}
	b.Write([]byte("second\n"))
	text, next, truncated := b.ReadSince(cursor)
	if text != "second\n" || next <= cursor || truncated {
		t.Fatal("incorrect incremental read")
	}
	b.Write([]byte("third-line-long\n"))
	text, _, truncated = b.ReadSince(0)
	if text != "second\nthird-line-long\n" || !truncated {
		t.Fatalf("incorrect rollover: %q", text)
	}
	if output.String() != "first\nsecond\nthird-line-long\n" {
		t.Fatal("stdout was changed or truncated")
	}
	if b.Instance() == "" {
		t.Fatal("missing process identifier")
	}
}
