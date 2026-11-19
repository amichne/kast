package frontend

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
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
	status := f.runProcess(strings.NewReader(stdin), &stdout, &stderr, signals, nil, arguments...)
	return outcome{status, stdout.String(), stderr.String()}
}

func (f *fixture) runProcess(stdin io.Reader, stdout, stderr io.Writer, signals <-chan os.Signal, environment []string,
	arguments ...string) int {
	if signals == nil {
		signals = make(chan os.Signal)
	}
	return Run(Process{
		Executable:  filepath.Join(f.root, "bin", "kast"),
		Arguments:   arguments,
		Environment: append([]string{"HOME=" + f.root, "KAST_FIXTURE=caller"}, environment...),
		Directory:   f.root,
		Stdin:       stdin,
		Stdout:      stdout,
		Stderr:      stderr,
		Signals:     signals,
	})
}

// blockedReader reports its first read and never completes it, as a terminal does while nobody types.
type blockedReader struct {
	reading  chan struct{}
	released chan struct{}
}

func (r blockedReader) Read([]byte) (int, error) {
	select {
	case <-r.reading:
	default:
		close(r.reading)
	}
	<-r.released
	return 0, io.EOF
}

// blockedWriter reports its first write and never completes it, as a stalled stdout pipe does.
type blockedWriter struct {
	writing  chan struct{}
	released chan struct{}
}

func (w blockedWriter) Write([]byte) (int, error) {
	select {
	case <-w.writing:
	default:
		close(w.writing)
	}
	<-w.released
	return 0, io.ErrClosedPipe
}

// statusWithin fails the test unless run returns within the bound, so a hang is a failure rather than a timeout.
func statusWithin(t *testing.T, bound time.Duration, run func() int) int {
	t.Helper()
	finished := make(chan int, 1)
	go func() { finished <- run() }()
	select {
	case status := <-finished:
		return status
	case <-time.After(bound):
		t.Fatal("the frontend did not return")
		return 0
	}
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
		"UNSUPPORTED_VERSION": 6, "INVALID_REQUEST": 6, "CAPACITY_EXCEEDED": 2, "INPUT_EXPIRED": 2,
		"LIFECYCLE_TRANSITION": 4, "UNAVAILABLE": 4,
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

func TestASignalEndsTheInvocationWhileStdinIsBlocked(t *testing.T) {
	f := newFixture(t)
	touchFrontend(t, f)
	cancelled := make(chan struct{})
	f.serve(t, func(ctx context.Context, conn *websocket.Conn, _ invocationRequest) {
		send(t, ctx, conn, map[string]any{"type": "input_required", "maximumBytes": 5})
		if _, _, err := conn.Read(ctx); err != nil {
			close(cancelled)
		}
	})
	stdin := blockedReader{reading: make(chan struct{}), released: make(chan struct{})}
	t.Cleanup(func() { close(stdin.released) })
	signals := make(chan os.Signal, 1)
	go func() {
		<-stdin.reading
		signals <- syscall.SIGINT
	}()
	var stdout, stderr bytes.Buffer
	status := statusWithin(t, 5*time.Second, func() int {
		return f.runProcess(stdin, &stdout, &stderr, signals, nil, "query", "--request", "-")
	})
	if status != 130 || stdout.Len() != 0 {
		t.Fatalf("unexpected outcome %d %q", status, stdout.String())
	}
	select {
	case <-cancelled:
	case <-time.After(5 * time.Second):
		t.Fatal("the coordinator connection stayed open after the signal")
	}
}

func TestASignalEndsTheInvocationWhileStdoutIsBlocked(t *testing.T) {
	f := newFixture(t)
	touchFrontend(t, f)
	f.serve(t, func(ctx context.Context, conn *websocket.Conn, _ invocationRequest) {
		send(t, ctx, conn, completed(0, "document"))
	})
	stdout := blockedWriter{writing: make(chan struct{}), released: make(chan struct{})}
	t.Cleanup(func() { close(stdout.released) })
	signals := make(chan os.Signal, 1)
	go func() {
		<-stdout.writing
		signals <- syscall.SIGTERM
	}()
	var stderr bytes.Buffer
	status := statusWithin(t, 5*time.Second, func() int {
		return f.runProcess(strings.NewReader(""), stdout, &stderr, signals, nil, "query")
	})
	if status != 143 {
		t.Fatalf("unexpected status %d", status)
	}
}

func TestAnUnansweredHandshakeIsUnreachableWithinTheCatalogBound(t *testing.T) {
	f := newFixture(t)
	touchFrontend(t, f)
	document := validCatalog()
	document["handshakeMilliseconds"] = 200
	writeCatalog(t, f.root, document)
	// The kernel accepts the connection, but no upgrade reply ever arrives.
	listener, err := net.Listen("unix", filepath.Join(f.root, "state", "run", "c.sock"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { listener.Close() })
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			t.Cleanup(func() { conn.Close() })
		}
	}()
	marker := f.bootstrap(t, 0, "")
	var stdout, stderr bytes.Buffer
	status := statusWithin(t, 5*time.Second, func() int {
		return f.runProcess(strings.NewReader(""), &stdout, &stderr, nil, nil, "query")
	})
	runs, _ := os.ReadFile(marker)
	if status != 9 || strings.Count(string(runs), "run") != 1 || stdout.Len() != 0 {
		t.Fatalf("unexpected outcome %d %q runs=%q", status, stderr.String(), runs)
	}
}

func TestHelpWithAnUnadmittedOutputSettingIsAnsweredByTheCoordinator(t *testing.T) {
	f := newFixture(t)
	touchFrontend(t, f)
	f.serve(t, func(ctx context.Context, conn *websocket.Conn, _ invocationRequest) {
		send(t, ctx, conn, completed(2, "rejection"))
	})
	var stdout, stderr bytes.Buffer
	status := f.runProcess(strings.NewReader(""), &stdout, &stderr, nil, []string{"KAST_OUTPUT_TOON=yes"}, "query", "--help")
	if status != 2 || stdout.String() != "rejection\n" || f.connections.Load() != 1 {
		t.Fatalf("unexpected outcome %d %q connections=%d", status, stdout.String(), f.connections.Load())
	}
}
