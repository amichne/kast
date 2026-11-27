package frontend

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/amichne/kast/frontend/internal/agent"
	"github.com/amichne/kast/frontend/internal/install"
	"github.com/amichne/kast/frontend/internal/reset"
)

// fixture is one installation with a scripted coordinator on its real Unix socket.
type fixture struct {
	root        string
	connections atomic.Int32
	requests    chan invocationRequest
	closed      chan struct{}
	executed    [][]string
}

// newFixture lays out a root under /tmp, keeping the socket path within the Unix limit.
func newFixture(t *testing.T) *fixture {
	t.Helper()
	base, err := os.MkdirTemp("/tmp", "kf")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(base) })
	// The installation's parent holds its management receipt, as the installer lays it out.
	root, err := filepath.EvalSymlinks(base)
	if err != nil {
		t.Fatal(err)
	}
	root = filepath.Join(root, "installation")
	writeCatalog(t, root, validCatalog())
	for _, directory := range []string{"share/kast/libexec", "libexec", "state/run"} {
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
		Executable:  filepath.Join(f.root, payloadPath),
		Arguments:   arguments,
		Environment: append([]string{"HOME=" + f.root, "KAST_FIXTURE=caller"}, environment...),
		Directory:   f.root,
		Stdin:       stdin,
		Stdout:      stdout,
		Stderr:      stderr,
		Signals:     signals,
		Exec: func(_ string, argv []string, _ []string) error {
			f.executed = append(f.executed, argv)
			return errors.New("exec is recorded by the fixture")
		},
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
	if err := os.WriteFile(filepath.Join(f.root, payloadPath), nil, 0o700); err != nil {
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

func TestFrontendTraceIdentifiesCallerAndServingCoordinator(t *testing.T) {
	f := newFixture(t)
	touchFrontend(t, f)
	f.serve(t, func(ctx context.Context, conn *websocket.Conn, _ invocationRequest) {
		send(t, ctx, conn, completed(0, "result"))
	})
	var output, diagnostics bytes.Buffer
	status := f.runProcess(strings.NewReader(""), &output, &diagnostics, nil, []string{"KAST_DEBUG=1"}, "query", "--name", "Fixture")
	var event struct {
		Type              string
		CallerProcessID   int
		ProcessID         int64
		ServiceGeneration string
	}
	if err := json.Unmarshal(diagnostics.Bytes(), &event); err != nil {
		t.Fatal(err)
	}
	if status != 0 || event.Type != "frontend_trace" || event.CallerProcessID != os.Getpid() ||
		event.ProcessID != 7 || event.ServiceGeneration != "generation" {
		t.Fatalf("unexpected trace %+v (status %d)", event, status)
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

func TestAnUnreachableCoordinatorLeavesTheInvocationToTheSameJvmEntry(t *testing.T) {
	f := newFixture(t)
	touchFrontend(t, f)
	f.receipt(t, "/runtimes/abc")
	f.run(t, "", nil, "-Duser.home=/home/caller", "query", "--name", "Fixture")
	if len(f.executed) != 1 || f.connections.Load() != 0 {
		t.Fatalf("executed=%v connections=%d", f.executed, f.connections.Load())
	}
	tail := f.executed[0][len(f.executed[0])-4:]
	if strings.Join(tail, " ") != "--internal-entrypoint=management query --name Fixture" ||
		!slices.Contains(f.executed[0], "-Duser.home=/home/caller") {
		t.Fatalf("the JVM entry did not receive the unchanged invocation: %v", f.executed[0])
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
	f.receipt(t, "/runtimes/abc")
	f.run(t, "", nil, "query")
	if f.connections.Load() != 0 || len(f.executed) != 1 {
		t.Fatalf("an unprotected socket was trusted: connections=%d executed=%v", f.connections.Load(), f.executed)
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
	f.receipt(t, "/runtimes/abc")
	var stdout, stderr bytes.Buffer
	statusWithin(t, 5*time.Second, func() int {
		return f.runProcess(strings.NewReader(""), &stdout, &stderr, nil, nil, "query")
	})
	if len(f.executed) != 1 || stdout.Len() != 0 {
		t.Fatalf("an unanswered handshake did not reach the JVM entry: %q executed=%v", stderr.String(), f.executed)
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

// receipt records a Host runtime root the way the installer's management receipt does.
func (f *fixture) receipt(t *testing.T, runtimeRoot string) {
	t.Helper()
	encoded, _ := json.Marshal(map[string]any{"product": map[string]any{"runtimeRoot": runtimeRoot}})
	if err := os.WriteFile(filepath.Join(filepath.Dir(f.root), "management.json"), encoded, 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestEveryEntryOtherThanPublicQueriesReplacesTheProcessWithTheJvmEntry(t *testing.T) {
	f := newFixture(t)
	touchFrontend(t, f)
	f.receipt(t, "/runtimes/abc")
	for _, arguments := range [][]string{
		{"-Duser.home=/home/caller", "--internal-entrypoint=mcp", "--catalog", "common"},
		{"-Xmx512m", "-XX:+UseSerialGC", "--internal-entrypoint=jq", "FILTER"},
		{"workspace", "session-start"},
		{"--internal-entrypoint=query", "hook", "pre-tool"},
	} {
		f.executed = nil
		if got := f.run(t, "", nil, arguments...); got.status != 9 || len(f.executed) != 1 || f.connections.Load() != 0 {
			t.Fatalf("%v: unexpected outcome %+v executed=%v", arguments, got, f.executed)
		}
	}
	want := []string{"/runtimes/abc/host/jbr/bin/java", "-Xlog:disable", "-Xlog:all=warning:stderr",
		"-Dkast.command=" + filepath.Join(f.root, payloadPath),
		"-Duser.home=/home/caller", "-Xmx256m", "-cp",
		filepath.Join(f.root, "lib/kast-cli.jar") + ":" + filepath.Join(f.root, "lib/kotlin-stdlib.jar"),
		"io.github.amichne.kast.cli.KastControlMain", "--internal-entrypoint=mcp", "--catalog", "common"}
	f.executed = nil
	f.run(t, "", nil, "-Duser.home=/home/caller", "-Xmx256m", "--internal-entrypoint=mcp", "--catalog", "common")
	if !slices.Equal(f.executed[0], want) {
		t.Fatalf("JVM command %v, want %v", f.executed[0], want)
	}
}

func TestPublicQueriesFromTheQueryEntryReachTheCoordinatorWithoutTheSelector(t *testing.T) {
	f := newFixture(t)
	touchFrontend(t, f)
	f.serve(t, func(ctx context.Context, conn *websocket.Conn, _ invocationRequest) {
		send(t, ctx, conn, completed(0, "served"))
	})
	got := f.run(t, "", nil, "-Duser.home=/home/caller", "--internal-entrypoint=query", "query", "--name", "Fixture")
	request := <-f.requests
	if got.status != 0 || len(f.executed) != 0 || strings.Join(request.Arguments, " ") != "query --name Fixture" {
		t.Fatalf("unexpected outcome %+v request=%v executed=%v", got, request.Arguments, f.executed)
	}
}

func TestAMissingRuntimeReceiptIsABootstrapFailureBeforeAnyExec(t *testing.T) {
	f := newFixture(t)
	touchFrontend(t, f)
	if got := f.run(t, "", nil, "install"); got.status != 9 || len(f.executed) != 0 ||
		!strings.Contains(got.stderr, "runtime is unavailable") {
		t.Fatalf("unexpected outcome %+v executed=%v", got, f.executed)
	}
}

// The scripted reset is the only effect these processes permit: Exec fails the test, and no installation exists.
func resetProcess(t *testing.T, reset func(string, []string, io.Writer) error, stderr io.Writer,
	arguments ...string) Process {
	t.Helper()
	return Process{
		Executable:  filepath.Join(t.TempDir(), "absent", "kast"),
		Arguments:   arguments,
		Environment: []string{"HOME=/selected/home"},
		Stdout:      io.Discard,
		Stderr:      stderr,
		Signals:     make(chan os.Signal),
		Exec: func(string, []string, []string) error {
			t.Error("a reset must not start the JVM")
			return errors.New("unexpected exec")
		},
		Reset: reset,
	}
}

func TestAResetRunsWithoutAnInstallationAndMapsItsFailuresToPublishedStatuses(t *testing.T) {
	for want, failure := range map[int]error{
		0: nil,
		2: &reset.Rejection{Failure: reset.UsageRejected, Detail: "usage"},
		4: &reset.Rejection{Failure: reset.BackupRejected, Detail: "unreadable"},
	} {
		var stderr bytes.Buffer
		calls := 0
		status := Run(resetProcess(t, func(home string, arguments []string, _ io.Writer) error {
			calls++
			if home != "/selected/home" || !slices.Equal(arguments, []string{"--yes"}) {
				t.Errorf("reset of %q with %v", home, arguments)
			}
			return failure
		}, &stderr, "-Duser.home=/selected/home", "reset", "--yes"))
		if status != want || calls != 1 || (failure != nil && !strings.Contains(stderr.String(), failure.Error())) {
			t.Errorf("status %d after %d calls, want %d: %s", status, calls, want, stderr.String())
		}
	}
}

func TestASignalEndsAReset(t *testing.T) {
	for signal, want := range map[os.Signal]int{syscall.SIGINT: 130, syscall.SIGTERM: 143} {
		released := make(chan struct{})
		t.Cleanup(func() { close(released) })
		signals := make(chan os.Signal, 1)
		signals <- signal
		process := resetProcess(t, func(string, []string, io.Writer) error { <-released; return nil }, io.Discard, "reset")
		process.Signals = signals
		if status := statusWithin(t, 5*time.Second, func() int { return Run(process) }); status != want {
			t.Errorf("%v: status %d, want %d", signal, status, want)
		}
	}
}

func TestAnAgentCommandRunsHereAndMapsItsFailuresToPublishedStatuses(t *testing.T) {
	f := newFixture(t)
	for want, failure := range map[int]error{
		0: nil,
		2: &agent.Rejection{Failure: agent.UsageRejected, Detail: "usage", Next: "try this"},
		4: &agent.Rejection{Failure: agent.MarketplaceForeign, Detail: "another kast", Next: "try this"},
	} {
		for expected, executable := range map[string]string{
			f.root: filepath.Join(f.root, payloadPath),
			// A removal needs no installation, so its absence reaches the agent command.
			"": filepath.Join(t.TempDir(), "absent", "kast"),
		} {
			var stderr bytes.Buffer
			calls := 0
			process := resetProcess(t, nil, &stderr, "agent", "remove", "codex")
			process.Executable = executable
			process.Agent = func(_ context.Context, installation string, arguments []string, _ io.Reader, _ io.Writer) error {
				calls++
				if installation != expected || !slices.Equal(arguments, []string{"remove", "codex"}) {
					t.Errorf("agent command on %q with %v", installation, arguments)
				}
				return failure
			}
			if expected != "" {
				touchFrontend(t, f)
			}
			status := Run(process)
			if status != want || calls != 1 ||
				(failure != nil && !strings.Contains(stderr.String(), failure.Error()+"\ntry this")) {
				t.Errorf("status %d after %d calls, want %d: %s", status, calls, want, stderr.String())
			}
		}
	}
}

func TestASignalEndsAnAgentCommand(t *testing.T) {
	for signal, want := range map[os.Signal]int{syscall.SIGINT: 130, syscall.SIGTERM: 143} {
		signals := make(chan os.Signal, 1)
		signals <- signal
		process := resetProcess(t, nil, io.Discard, "agent", "install", "claude")
		process.Signals = signals
		process.Agent = func(ctx context.Context, _ string, _ []string, _ io.Reader, _ io.Writer) error {
			<-ctx.Done()
			return ctx.Err()
		}
		if status := statusWithin(t, 5*time.Second, func() int { return Run(process) }); status != want {
			t.Errorf("%v: status %d, want %d", signal, status, want)
		}
	}
}

func TestOnlyTheManagementEntryResets(t *testing.T) {
	f := newFixture(t)
	touchFrontend(t, f)
	f.receipt(t, "/runtimes/abc")
	for _, arguments := range [][]string{{"--internal-entrypoint=daemon", "reset"}, {"install", "reset"}} {
		f.executed = nil
		// A reset here would panic: the fixture provides none.
		if f.run(t, "", nil, arguments...); len(f.executed) != 1 {
			t.Errorf("%v: executed %v", arguments, f.executed)
		}
	}
}

func TestAnInstallationRunsWithoutAnInstallationAndMapsItsFailuresToPublishedStatuses(t *testing.T) {
	for want, failure := range map[int]error{
		0: nil,
		2: &install.Rejection{Failure: install.UsageRejected, Detail: "usage", Next: "try this"},
		4: &install.Rejection{Failure: install.DownloadInvalid, Detail: "digest", Next: "try that"},
	} {
		var stderr bytes.Buffer
		calls := 0
		process := resetProcess(t, nil, &stderr, "install", "--dry-run", "--json")
		process.Install = func(_ context.Context, home, target string, arguments []string, _, _ io.Writer) error {
			calls++
			if home != "/selected/home" || target != platform() || !slices.Equal(arguments, []string{"--dry-run", "--json"}) {
				t.Errorf("installation in %q for %q with %v", home, target, arguments)
			}
			return failure
		}
		status := Run(process)
		if status != want || calls != 1 || (failure != nil && !strings.Contains(stderr.String(), "try th")) {
			t.Errorf("status %d after %d calls, want %d: %s", status, calls, want, stderr.String())
		}
	}
}

func TestASignalCancelsAnInstallation(t *testing.T) {
	for signal, want := range map[os.Signal]int{syscall.SIGINT: 130, syscall.SIGTERM: 143} {
		signals, cancelled := make(chan os.Signal, 1), make(chan struct{})
		signals <- signal
		process := resetProcess(t, nil, io.Discard, "install", "--download-only")
		process.Signals = signals
		process.Install = func(ctx context.Context, _, _ string, _ []string, _, _ io.Writer) error {
			<-ctx.Done()
			close(cancelled)
			return ctx.Err()
		}
		if status := statusWithin(t, 5*time.Second, func() int { return Run(process) }); status != want {
			t.Errorf("%v: status %d, want %d", signal, status, want)
		}
		<-cancelled
	}
}

func TestAnInterruptedInstallationRestoresItsTerminalBeforeTheMessage(t *testing.T) {
	signals := make(chan os.Signal, 1)
	signals <- syscall.SIGINT
	var stderr bytes.Buffer
	process := resetProcess(t, nil, &stderr, "install", "--download-only")
	process.Signals = signals
	process.Install = func(ctx context.Context, _, _ string, _ []string, _, log io.Writer) error {
		<-ctx.Done()
		time.Sleep(50 * time.Millisecond)
		fmt.Fprint(log, "restored\n")
		return &install.Rejection{Failure: install.Interrupted, Detail: "a file", Next: "again"}
	}
	if status := statusWithin(t, installGrace/2, func() int { return Run(process) }); status != 130 {
		t.Errorf("status %d", status)
	}
	if want := "restored\nkast: install: interrupted. Run the same command again to continue.\n"; stderr.String() != want {
		t.Errorf("got %q, want %q", stderr.String(), want)
	}
}

func TestAnInstallationThatDoesNotEndCannotBlockASignal(t *testing.T) {
	signals, release := make(chan os.Signal, 1), make(chan struct{})
	defer close(release)
	signals <- syscall.SIGTERM
	process := resetProcess(t, nil, io.Discard, "install", "--download-only")
	process.Signals = signals
	process.Install = func(context.Context, string, string, []string, io.Writer, io.Writer) error {
		<-release
		return nil
	}
	if status := statusWithin(t, 2*installGrace, func() int { return Run(process) }); status != 143 {
		t.Errorf("status %d", status)
	}
}

func TestOnlyInstallOptionsOfTheManagementEntryInstall(t *testing.T) {
	f := newFixture(t)
	touchFrontend(t, f)
	f.receipt(t, "/runtimes/abc")
	for _, arguments := range [][]string{{"install"}, {"install", "skill"}, {"install", "skill", "--dry-run"},
		{"install", "--help"}, {"install", "-h"}, {"--internal-entrypoint=daemon", "install", "--dry-run"}} {
		f.executed = nil
		// An installation here would panic: the fixture provides none.
		if f.run(t, "", nil, arguments...); len(f.executed) != 1 {
			t.Errorf("%v: executed %v", arguments, f.executed)
		}
	}
}

func TestThePublicCopyLocatesTheInstallationBeneathHome(t *testing.T) {
	home := t.TempDir()
	installation := filepath.Join(home, ".local", "share", "kast", "installation")
	if err := os.MkdirAll(installation, 0o700); err != nil {
		t.Fatal(err)
	}
	public := filepath.Join(home, ".local", "bin", "kast")
	if err := os.MkdirAll(filepath.Dir(public), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(public, nil, 0o700); err != nil {
		t.Fatal(err)
	}
	located, err := locateInstallation(public, map[string]string{"HOME": home})
	resolved, _ := filepath.EvalSymlinks(installation)
	if err != nil || located != resolved {
		t.Fatalf("located %q %v, want %q", located, err, resolved)
	}
}

func TestALongSocketIsDialedThroughTheCoordinatorsAliasOnly(t *testing.T) {
	f := newFixture(t)
	touchFrontend(t, f)
	f.serve(t, func(ctx context.Context, conn *websocket.Conn, _ invocationRequest) {
		send(t, ctx, conn, completed(0, "served"))
	})
	document := validCatalog()
	// Every real fixture socket is short, so a zero-byte bound makes this one "too long" to bind directly.
	// The alias itself must be short, as /tmp is for the coordinator.
	aliasDirectory, err := os.MkdirTemp("/tmp", "ka")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(aliasDirectory) })
	document["socketAlias"] = map[string]any{
		"maximumPathBytes": 1, "directory": aliasDirectory, "prefix": "kast-uds-", "digestCharacters": 32,
	}
	writeCatalog(t, f.root, document)
	run := filepath.Join(f.root, "state", "run")
	digest := sha256.Sum256([]byte(run))
	link := filepath.Join(aliasDirectory, "kast-uds-"+hex.EncodeToString(digest[:])[:32])
	f.receipt(t, "/runtimes/abc")
	if f.run(t, "", nil, "query"); f.connections.Load() != 0 || len(f.executed) != 1 {
		t.Fatalf("a missing alias was not unreachable: executed=%v", f.executed)
	}
	if err := os.Symlink(t.TempDir(), link); err != nil {
		t.Fatal(err)
	}
	if f.run(t, "", nil, "query"); f.connections.Load() != 0 || len(f.executed) != 2 {
		t.Fatalf("an alias to another directory was dialed: executed=%v", f.executed)
	}
	if err := os.Remove(link); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(run, link); err != nil {
		t.Fatal(err)
	}
	if got := f.run(t, "", nil, "query"); got.status != 0 || got.stdout != "served\n" {
		t.Fatalf("the exact alias was not dialed: %+v", got)
	}
}

func TestTheInstalledClassDataArchiveIsUsedOnlyWhenItExists(t *testing.T) {
	f := newFixture(t)
	touchFrontend(t, f)
	f.receipt(t, "/runtimes/abc")
	archive := filepath.Join(f.root, "lib", "control.jsa")
	option := "-XX:SharedArchiveFile=" + archive
	f.run(t, "", nil, "--version")
	if slices.Contains(f.executed[0], option) {
		t.Fatalf("an absent archive was passed: %v", f.executed[0])
	}
	if err := os.MkdirAll(filepath.Dir(archive), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink("/elsewhere/control.jsa", archive); err != nil {
		t.Fatal(err)
	}
	f.run(t, "", nil, "--version")
	if slices.Contains(f.executed[1], option) {
		t.Fatalf("a linked archive was passed: %v", f.executed[1])
	}
	if err := os.Remove(archive); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(archive, []byte("archive"), 0o600); err != nil {
		t.Fatal(err)
	}
	f.run(t, "", nil, "--version")
	cp := slices.Index(f.executed[2], "-cp")
	at := slices.Index(f.executed[2], option)
	if at < 0 || at > cp {
		t.Fatalf("the archive must precede the class path: %v", f.executed[2])
	}
}
