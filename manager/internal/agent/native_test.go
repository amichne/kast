package agent

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// program writes one executable and returns its path.
func program(t *testing.T, body string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "program")
	if err := os.WriteFile(path, []byte("#!/bin/sh\n"+body), 0o700); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestACommandReturnsOnlyStandardOutputAndReadsNoInput(t *testing.T) {
	output, err := runCommand(context.Background(), []string{program(t, "echo warning >&2; printf '[%s]' \"$(cat)\"\n")})
	if err != nil || string(output) != "[]" {
		t.Errorf("%q %v", output, err)
	}
}

func TestAFailedCommandReportsItsLastLineWithoutControlCharacters(t *testing.T) {
	_, err := runCommand(context.Background(), []string{program(t,
		`printf 'first\n\033[31mno login\033[0m\n\n' >&2; exit 3`+"\n")})
	if err == nil || !strings.Contains(err.Error(), "exit status 3") || !strings.HasSuffix(err.Error(), "no login[0m") ||
		strings.ContainsRune(err.Error(), '\033') || strings.Contains(err.Error(), "first") {
		t.Errorf("%q", err)
	}
	if line := lastLine([]byte(strings.Repeat("é", 400))); len([]rune(line)) != maximumMessage+3 {
		t.Errorf("a message of %d characters", len([]rune(line)))
	}
	if line := lastLine(nil, []byte("\n \n")); line != "" {
		t.Errorf("%q", line)
	}
}

func TestACommandEndsWithItsContextEvenWhenAHelperHoldsItsPipes(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	ready := filepath.Join(t.TempDir(), "ready")
	go func() {
		for {
			if _, err := os.Stat(ready); err == nil {
				cancel()
				return
			}
			time.Sleep(5 * time.Millisecond)
		}
	}()
	started := time.Now()
	_, err := runCommand(ctx, []string{program(t, "sleep 30 &\ntouch "+ready+"\nwait\n")})
	if !errors.Is(err, context.Canceled) || time.Since(started) > 10*time.Second {
		t.Errorf("%v after %v", err, time.Since(started))
	}
}

func TestAReplyBeyondItsBoundIsAFailure(t *testing.T) {
	buffer := &bounded{limit: 4}
	for _, part := range []string{"ab", "cd", "ef"} {
		if written, err := buffer.Write([]byte(part)); written != 2 || err != nil {
			t.Fatalf("%d %v", written, err)
		}
	}
	if buffer.buffer.String() != "abcd" || !buffer.exceeded {
		t.Errorf("%q exceeded=%v", buffer.buffer.String(), buffer.exceeded)
	}
	_, err := runCommand(context.Background(), []string{program(t,
		fmt.Sprintf("head -c %d /dev/zero\n", maximumReply+1))})
	if err == nil || !strings.Contains(err.Error(), "too long") {
		t.Errorf("%v", err)
	}
}

func TestTheManifestMustBeASmallRegularFile(t *testing.T) {
	directory := t.TempDir()
	path := filepath.Join(directory, "package.json")
	for _, absent := range []string{directory, path} {
		if _, err := readManifest(absent); err == nil {
			t.Errorf("%s passed", absent)
		}
	}
	if err := os.WriteFile(path, make([]byte, maximumManifest+1), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := readManifest(path); err == nil {
		t.Error("a large file passed")
	}
	if err := os.WriteFile(path, []byte("{}"), 0o600); err != nil {
		t.Fatal(err)
	}
	if raw, err := readManifest(path); err != nil || string(raw) != "{}" {
		t.Errorf("%q %v", raw, err)
	}
}

func TestAnAnswerIsOneLineAndAnEndOfInputIsNoAnswer(t *testing.T) {
	var shown bytes.Buffer
	answer, err := ask(context.Background(), strings.NewReader(" 1 2 \nrest"), &shown, "choose: ")
	if err != nil || answer != "1 2" || shown.String() != "choose: " {
		t.Errorf("%q %v after %q", answer, err, shown.String())
	}
	for _, input := range []string{"", "1"} {
		_, err := ask(context.Background(), strings.NewReader(input), io.Discard, "")
		if rejection := failure(t, err); rejection.Failure != UsageRejected {
			t.Errorf("%q: %v", input, rejection)
		}
	}
}

func TestACancelledQuestionEnds(t *testing.T) {
	reader, writer := io.Pipe()
	defer func() { _ = writer.Close() }()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := ask(ctx, reader, io.Discard, ""); !errors.Is(err, context.Canceled) {
		t.Error(err)
	}
}

func TestOnlyATerminalIsAsked(t *testing.T) {
	file, err := os.Create(filepath.Join(t.TempDir(), "file"))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = file.Close() }()
	if terminal(file) || terminal(strings.NewReader("")) || terminal(nil) {
		t.Error("a stream that is not a terminal passed")
	}
}
