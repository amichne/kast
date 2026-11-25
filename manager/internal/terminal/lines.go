package terminal

import (
	"bytes"
	"io"
	"strings"
	"sync"
)

// maximumLine bounds what Lines holds back. A longer line passes through unchanged.
const maximumLine = 1 << 20

// Lines renders each JSON document that fills one line as words, and passes every other line through. A part of a line
// that cannot start a document is written at once, so a prompt stays visible.
type Lines struct {
	Out io.Writer

	mutex sync.Mutex
	held  bytes.Buffer
	open  bool
}

func (l *Lines) Write(data []byte) (int, error) {
	l.mutex.Lock()
	defer l.mutex.Unlock()
	written := len(data)
	for len(data) > 0 {
		end := bytes.IndexByte(data, '\n')
		part := data
		if end >= 0 {
			part = data[:end+1]
		}
		data = data[len(part):]
		if err := l.accept(part, end >= 0); err != nil {
			return 0, err
		}
	}
	return written, nil
}

func (l *Lines) accept(part []byte, ended bool) error {
	candidate := l.held.Len() > 0 || (!l.open && (part[0] == '{' || part[0] == '['))
	if !candidate {
		l.open = !ended
		_, err := l.Out.Write(part)
		return err
	}
	l.held.Write(part)
	if !ended && l.held.Len() <= maximumLine {
		return nil
	}
	l.open = !ended
	return l.release()
}

func (l *Lines) release() error {
	text := l.held.String()
	l.held.Reset()
	_, err := io.WriteString(l.Out, Line(strings.TrimSuffix(text, "\n"))+strings.Repeat("\n", strings.Count(text, "\n")))
	return err
}

// Flush writes a last line that has no end.
func (l *Lines) Flush() error {
	l.mutex.Lock()
	defer l.mutex.Unlock()
	if l.held.Len() == 0 {
		return nil
	}
	return l.release()
}

// Line renders a one-line JSON document on one line, for progress and diagnostics.
func Line(text string) string {
	rendered := Document(text)
	if rendered == text {
		return text
	}
	var fields []string
	for line := range strings.Lines(rendered) {
		if field := strings.TrimSpace(line); field != "" {
			fields = append(fields, field)
		}
	}
	return "kast: " + strings.ReplaceAll(strings.Join(fields, ", "), ":, ", ": ")
}
