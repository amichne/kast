package agent

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strings"
	"time"
	"unicode"
)

// Each bound covers one agent command. A list that exceeds its bound is a failure, never a shorter list.
const (
	commandTime    = 2 * time.Minute
	maximumReply   = 4 << 20
	maximumMessage = 300
)

// Native performs one invocation with the agent commands on this machine.
func Native(ctx context.Context, installation string, arguments []string, out io.Writer) error {
	return Run(ctx, Effects{Look: exec.LookPath, Run: runCommand, File: regular}, installation, arguments, out)
}

func regular(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Mode().IsRegular()
}

// runCommand gives an agent no input, so a prompt ends the command instead of waiting for an answer.
func runCommand(ctx context.Context, vector []string) ([]byte, error) {
	ctx, cancel := context.WithTimeout(ctx, commandTime)
	defer cancel()
	reply, message := &bounded{limit: maximumReply}, &bounded{limit: maximumReply}
	command := exec.CommandContext(ctx, vector[0], vector[1:]...)
	command.Stdout, command.Stderr = reply, message
	// An agent can leave a helper that holds its pipes. Its exit still ends the wait.
	command.WaitDelay = time.Second
	err := command.Run()
	switch {
	case ctx.Err() != nil:
		return nil, fmt.Errorf("it did not finish: %w", ctx.Err())
	case err != nil:
		return nil, fmt.Errorf("%w%s", err, lastLine(message.buffer.Bytes(), reply.buffer.Bytes()))
	case reply.exceeded:
		return nil, errors.New("its reply is too long")
	}
	return reply.buffer.Bytes(), nil
}

// bounded keeps the first bytes and records that more arrived. The buffer is a field, because an embedded one would
// promote ReadFrom, which a copy prefers and which has no bound.
type bounded struct {
	buffer   bytes.Buffer
	limit    int
	exceeded bool
}

func (b *bounded) Write(data []byte) (int, error) {
	kept := data
	if room := b.limit - b.buffer.Len(); len(kept) > room {
		kept, b.exceeded = kept[:room], true
	}
	if _, err := b.buffer.Write(kept); err != nil {
		return 0, err
	}
	return len(data), nil
}

// lastLine is the final non-empty line of the first stream that has one, without control characters.
func lastLine(streams ...[]byte) string {
	for _, stream := range streams {
		lines := strings.FieldsFunc(string(stream), func(r rune) bool { return r == '\n' || r == '\r' })
		for index := len(lines) - 1; index >= 0; index-- {
			line := strings.TrimSpace(strings.Map(func(r rune) rune {
				if unicode.IsControl(r) {
					return -1
				}
				return r
			}, lines[index]))
			if line != "" {
				return ": " + truncate(line)
			}
		}
	}
	return ""
}

func truncate(line string) string {
	runes := []rune(line)
	if len(runes) <= maximumMessage {
		return line
	}
	return string(runes[:maximumMessage]) + "…"
}
