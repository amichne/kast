package terminal

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"slices"
	"syscall"
	"time"
)

const (
	recordSchema      = 1
	maximumRecord     = 64 << 10
	progressDelay     = time.Second
	progressInterval  = 250 * time.Millisecond
	eraseLine         = "\r\x1b[2K"
	maximumLineLength = 78
)

// steps orders the phases that a preparation passes. The Host derives the phase.
var steps = []struct{ phase, text string }{
	{"OPENING", "opening the project"},
	{"IMPORTING", "importing the Gradle model"},
	{"SCANNING", "scanning files"},
	{"INDEXING", "indexing"},
	{"ADMITTING", "checking readiness"},
}

// record is what this process reads of host-progress.json. HostProgressRecord owns the document.
type record struct {
	SchemaVersion int    `json:"schemaVersion"`
	HostPid       int    `json:"hostPid"`
	Phase         string `json:"phase"`
	Index         struct {
		QueuedFiles int `json:"queuedFiles"`
	} `json:"index"`
}

// Progress follows one workspace's preparation. It reads files only and never starts or asks a Host.
type Progress struct {
	// Store is the workspace store, which holds one container for each workspace root.
	Store string
	// Directory is where the caller stands. The nearest ancestor with a container is the workspace.
	Directory string
	// Alive reports whether a process exists.
	Alive func(pid int) bool
}

// Alive reports whether a process exists.
func Alive(pid int) bool { return pid > 0 && syscall.Kill(pid, 0) == nil }

// line describes the preparation now, or is empty when nothing is known.
func (p Progress) line() string {
	found, ok := p.read()
	if !ok || found.SchemaVersion != recordSchema || !p.Alive(found.HostPid) {
		return ""
	}
	for index, step := range steps {
		if step.phase != found.Phase {
			continue
		}
		text := fmt.Sprintf("(%d/%d): %s", index+1, len(steps), step.text)
		if found.Index.QueuedFiles > 0 {
			if found.Phase != "SCANNING" && found.Phase != "INDEXING" {
				text += ", indexing"
			}
			text += fmt.Sprintf(" ~%s files", grouped(found.Index.QueuedFiles))
		}
		return text
	}
	return ""
}

func (p Progress) read() (record, bool) {
	container, ok := p.container()
	if !ok {
		return record{}, false
	}
	raw, err := readSmall(filepath.Join(container, "generation.json"))
	if err != nil {
		return record{}, false
	}
	var selected struct {
		Generation *string `json:"generation"`
	}
	if json.Unmarshal(raw, &selected) != nil {
		return record{}, false
	}
	state := filepath.Join(container, "state")
	if selected.Generation != nil {
		if !filepath.IsLocal(*selected.Generation) {
			return record{}, false
		}
		state = filepath.Join(container, "generations", *selected.Generation, "state")
	}
	raw, err = readSmall(filepath.Join(state, "host-progress.json"))
	if err != nil {
		return record{}, false
	}
	var found record
	return found, json.Unmarshal(raw, &found) == nil
}

// container names the container of the nearest workspace. ContainerId.forWorkspace owns the digest.
func (p Progress) container() (string, bool) {
	root, err := filepath.EvalSymlinks(p.Directory)
	if err != nil {
		return "", false
	}
	for {
		digest := sha256.Sum256([]byte(root))
		container := filepath.Join(p.Store, "containers", hex.EncodeToString(digest[:]))
		if info, err := os.Lstat(container); err == nil && info.IsDir() {
			return container, true
		}
		parent := filepath.Dir(root)
		if parent == root {
			return "", false
		}
		root = parent
	}
}

func readSmall(path string) ([]byte, error) {
	info, err := os.Lstat(path)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() || info.Size() > maximumRecord {
		return nil, os.ErrInvalid
	}
	return os.ReadFile(path)
}

// Follow redraws one line on a terminal until the context ends, then erases it. A request that ends within the delay
// draws nothing.
func (p Progress) Follow(ctx context.Context, out io.Writer) {
	started := time.Now()
	wait := time.NewTimer(progressDelay)
	defer wait.Stop()
	drawn := ""
	for {
		select {
		case <-ctx.Done():
			if drawn != "" {
				io.WriteString(out, eraseLine)
			}
			return
		case <-wait.C:
			drawn = p.draw(out, drawn, time.Since(started))
			wait.Reset(progressInterval)
		}
	}
}

func (p Progress) draw(out io.Writer, drawn string, elapsed time.Duration) string {
	text := ""
	if line := p.line(); line != "" {
		text = fmt.Sprintf("Preparing %s, %ds", line, int(elapsed.Seconds()))
		if len(text) > maximumLineLength {
			text = text[:maximumLineLength]
		}
	}
	if text != drawn {
		io.WriteString(out, eraseLine+text)
	}
	return text
}

func grouped(count int) string {
	digits := fmt.Sprint(count)
	var groups []byte
	for index, digit := range []byte(digits) {
		if index > 0 && (len(digits)-index)%3 == 0 {
			groups = append(groups, ',')
		}
		groups = append(groups, digit)
	}
	return string(slices.Clip(groups))
}
