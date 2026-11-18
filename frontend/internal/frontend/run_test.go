package frontend

import (
	"bytes"
	"context"
	"encoding/json"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	"github.com/coder/websocket"
)

// fixture is one coordinator root with a scripted coordinator on its real Unix socket.
type fixture struct {
	root        string
	connections atomic.Int32
	requests    chan invocationRequest
	closed      chan struct{}
}

// newFixture lays out a root under /tmp, keeping the socket path within the Unix limit.
func newFixture(t *testing.T) *fixture {
	t.Helper()
	root, err := os.MkdirTemp("/tmp", "kf")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(root) })
	writeCatalog(t, root, validCatalog())
	for _, directory := range []string{"bin", "libexec", "state/run"} {
		if err := os.MkdirAll(filepath.Join(root, directory), 0o700); err != nil {
			t.Fatal(err)
		}
	}
	return &fixture{root: root, requests: make(chan invocationRequest, 4), closed: make(chan struct{}, 4)}
}

// serve answers each connection with script, after decoding the request it received.
func (f *fixture) serve(t *testing.T, script func(context.Context, *websocket.Conn, invocationRequest)) {
	t.Helper()
	listener, err := net.Listen("unix", filepath.Join(f.root, "state", "run", "c.sock"))
	if err != nil {
		t.Fatal(err)
	}
	server := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/kast-public-invocation" {
			http.NotFound(w, r)
			return
		}
		f.connections.Add(1)
		conn, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer conn.CloseNow()
		_, message, err := conn.Read(r.Context())
		if err != nil {
			return
		}
		var request invocationRequest
		if json.Unmarshal(message, &request) != nil {
			return
		}
		f.requests <- request
		script(r.Context(), conn, request)
	})}
	go server.Serve(listener)
	t.Cleanup(func() { server.Close() })
}

// bootstrap installs a start command that records each run and exits with status.
func (f *fixture) bootstrap(t *testing.T, status int, stderr string) string {
	t.Helper()
	marker := filepath.Join(f.root, "bootstrap-runs")
	script := "#!/bin/sh\necho run >> '" + marker + "'\nprintf '%s' '" + stderr + "' >&2\nexit " + strconv.Itoa(status) + "\n"
	if err := os.WriteFile(filepath.Join(f.root, "libexec", "kast-coordinator"), []byte(script), 0o700); err != nil {
		t.Fatal(err)
	}
	return marker
}

func send(t *testing.T, ctx context.Context, conn *websocket.Conn, value any) {
	encoded, err := json.Marshal(value)
	if err != nil {
		t.Error(err)
		return
	}
	if err := conn.Write(ctx, websocket.MessageText, encoded); err != nil {
		t.Error(err)
	}
}

type outcome struct {
	status int
	stdout string
	stderr string
}

func (f *fixture) run(t *testing.T, stdin string, signals <-chan os.Signal, arguments ...string) outcome {
	t.Helper()
	var stdout, stderr bytes.Buffer
	if signals == nil {
		signals = make(chan os.Signal)
	}
	status := Run(Process{
		Executable:  filepath.Join(f.root, "bin", "kast"),
		Arguments:   arguments,
		Environment: []string{"HOME=" + f.root, "KAST_FIXTURE=caller"},
		Directory:   f.root,
		Stdin:       strings.NewReader(stdin),
		Stdout:      &stdout,
		Stderr:      &stderr,
		Signals:     signals,
	})
	return outcome{status, stdout.String(), stderr.String()}
}

func completed(exitCode int, output string, diagnostics ...string) map[string]any {
	return map[string]any{
		"type":        "completed",
		"coordinator": map[string]any{"serviceGeneration": "generation", "processId": 7},
		"exitCode":    exitCode,
		"output":      output,
		"diagnostics": diagnostics,
	}
}

func touchFrontend(t *testing.T, f *fixture) {
	if err := os.WriteFile(filepath.Join(f.root, "bin", "kast"), nil, 0o700); err != nil {
		t.Fatal(err)
	}
}

func TestDeliversTheCoordinatorsDocumentStatusAndDiagnostics(t *testing.T) {
	f := newFixture(t)
	touchFrontend(t, f)
	f.serve(t, func(ctx context.Context, conn *websocket.Conn, _ invocationRequest) {
		send(t, ctx, conn, completed(10, `{"semantic":"incomplete"}`, "kast: guidance"))
	})
	got := f.run(t, "", nil, "query", "--name", "Fixture")
	if got.status != 10 || got.stdout != "{\"semantic\":\"incomplete\"}\n" || !strings.Contains(got.stderr, "kast: guidance") {
		t.Fatalf("unexpected outcome %+v", got)
	}
	request := <-f.requests
	if request.Version != 1 || request.Directory != f.root || request.Environment["KAST_FIXTURE"] != "caller" ||
		strings.Join(request.Arguments, " ") != "query --name Fixture" {
		t.Fatalf("unexpected request %+v", request)
	}
}

func TestAnswersHelpLocallyWithoutAConnection(t *testing.T) {
	f := newFixture(t)
	touchFrontend(t, f)
	f.serve(t, func(context.Context, *websocket.Conn, invocationRequest) {})
	got := f.run(t, "", nil, "query", "--help")
	if got.status != 0 || got.stdout != "kast query\n" || f.connections.Load() != 0 {
		t.Fatalf("help reached the coordinator or failed: %+v, connections=%d", got, f.connections.Load())
	}
}

func TestReadsStdinOnlyWhenRequestedAndWithinTheStatedBound(t *testing.T) {
	f := newFixture(t)
	touchFrontend(t, f)
	f.serve(t, func(ctx context.Context, conn *websocket.Conn, _ invocationRequest) {
		send(t, ctx, conn, map[string]any{"type": "input_required", "maximumBytes": 5})
		kind, input, err := conn.Read(ctx)
		if err != nil || kind != websocket.MessageBinary {
			t.Errorf("expected one binary input frame: %v %v", kind, err)
			return
		}
		send(t, ctx, conn, completed(0, string(input)))
	})
	if got := f.run(t, "abcdefgh", nil, "query", "--request", "-"); got.status != 0 || got.stdout != "abcde\n" {
		t.Fatalf("unexpected outcome %+v", got)
	}
}

func TestCoordinatorRejectionsMapToPublishedStatuses(t *testing.T) {
	for reason, want := range map[string]int{
		"UNSUPPORTED_VERSION": 6, "INVALID_REQUEST": 6, "CAPACITY_EXCEEDED": 2, "LIFECYCLE_TRANSITION": 4, "UNAVAILABLE": 4,
	} {
		f := newFixture(t)
		touchFrontend(t, f)
		f.serve(t, func(ctx context.Context, conn *websocket.Conn, _ invocationRequest) {
			send(t, ctx, conn, map[string]any{"type": "rejected", "failure": reason})
		})
		if got := f.run(t, "", nil, "query"); got.status != want || got.stdout != "" || !strings.Contains(got.stderr, reason) {
			t.Fatalf("%s: unexpected outcome %+v", reason, got)
		}
	}
}

func TestAConnectionLostAfterTheRequestReportsAnUnknownOutcome(t *testing.T) {
	f := newFixture(t)
	touchFrontend(t, f)
	f.serve(t, func(_ context.Context, conn *websocket.Conn, _ invocationRequest) { conn.CloseNow() })
	got := f.run(t, "", nil, "query")
	if got.status != 4 || got.stdout != "" || !strings.Contains(got.stderr, "outcome is unknown") {
		t.Fatalf("unexpected outcome %+v", got)
	}
}

func TestASignalBeforeCompletionOwnsTheStatusAndCancelsTheRequest(t *testing.T) {
	f := newFixture(t)
	touchFrontend(t, f)
	cancelled := make(chan struct{})
	f.serve(t, func(ctx context.Context, conn *websocket.Conn, _ invocationRequest) {
		if _, _, err := conn.Read(ctx); err != nil {
			close(cancelled)
		}
	})
	signals := make(chan os.Signal, 1)
	go func() {
		<-f.requests
		signals <- syscall.SIGTERM
	}()
	got := f.run(t, "", signals, "query")
	if got.status != 143 || got.stdout != "" {
		t.Fatalf("unexpected outcome %+v", got)
	}
	select {
	case <-cancelled:
	case <-time.After(5 * time.Second):
		t.Fatal("the coordinator connection stayed open after the signal")
	}
}

func TestAnUnreachableCoordinatorIsStartedOnceAndItsFailureRelayed(t *testing.T) {
	f := newFixture(t)
	touchFrontend(t, f)
	marker := f.bootstrap(t, 9, "startup-rejected")
	got := f.run(t, "", nil, "query")
	runs, _ := os.ReadFile(marker)
	if got.status != 9 || strings.Count(string(runs), "run") != 1 || !strings.Contains(got.stderr, "startup-rejected") {
		t.Fatalf("unexpected outcome %+v runs=%q", got, runs)
	}
}

func TestASocketInADirectoryOpenToOthersIsNeverDialed(t *testing.T) {
	f := newFixture(t)
	touchFrontend(t, f)
	f.serve(t, func(ctx context.Context, conn *websocket.Conn, _ invocationRequest) {
		send(t, ctx, conn, completed(0, ""))
	})
	if err := os.Chmod(filepath.Join(f.root, "state", "run"), 0o755); err != nil {
		t.Fatal(err)
	}
	marker := f.bootstrap(t, 9, "")
	got := f.run(t, "", nil, "query")
	runs, _ := os.ReadFile(marker)
	if got.status != 9 || f.connections.Load() != 0 || strings.Count(string(runs), "run") != 1 {
		t.Fatalf("an unprotected socket was trusted: %+v connections=%d", got, f.connections.Load())
	}
}
