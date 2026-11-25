package frontend

import (
	"bytes"
	"context"
	"errors"
	"io"
	"os"
	"slices"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/coder/websocket"
)

const completedDocument = `{"type":"complete","items":[{"name":"A"}]}`

func completes(t *testing.T) func(context.Context, *websocket.Conn, invocationRequest) {
	return func(ctx context.Context, conn *websocket.Conn, _ invocationRequest) {
		send(t, ctx, conn, completed(0, completedDocument))
	}
}

func installed(t *testing.T) *fixture {
	t.Helper()
	f := newFixture(t)
	touchFrontend(t, f)
	f.receipt(t, "/runtimes/abc")
	return f
}

// person runs one invocation whose standard output alone is a terminal.
func (f *fixture) person(t *testing.T, signals <-chan os.Signal, capture Capture, environment []string,
	arguments ...string) outcome {
	t.Helper()
	var stdout, stderr bytes.Buffer
	status := Run(Process{
		Executable:  f.root + "/" + payloadPath,
		Arguments:   arguments,
		Environment: append([]string{"HOME=" + f.root}, environment...),
		Directory:   f.root,
		Stdin:       strings.NewReader(""),
		Stdout:      &stdout,
		Stderr:      &stderr,
		Signals:     signals,
		Terminal:    func(stream any) bool { return stream == any(&stdout) },
		Capture:     capture,
		Exec: func(_ string, argv []string, _ []string) error {
			f.executed = append(f.executed, argv)
			return errors.New("exec is recorded by the fixture")
		},
	})
	return outcome{status, stdout.String(), stderr.String()}
}

func TestAPersonReadsAnOutlineAndEveryNamedFormatStaysExact(t *testing.T) {
	outline := "type: complete\nitems:\n  - name: A\n"
	for name, test := range map[string]struct {
		environment, arguments []string
		want                   string
	}{
		"person":        {nil, []string{"query", "--name", "A"}, outline},
		"json":          {nil, []string{"query", "--json"}, completedDocument + "\n"},
		"projection":    {nil, []string{"query", "--jq", "."}, completedDocument + "\n"},
		"format chosen": {[]string{"KAST_OUTPUT_TOON=0"}, []string{"query"}, completedDocument + "\n"},
	} {
		t.Run(name, func(t *testing.T) {
			f := installed(t)
			f.serve(t, completes(t))
			if got := f.person(t, nil, nil, test.environment, test.arguments...); got.status != 0 || got.stdout != test.want {
				t.Errorf("got %+v want %q", got, test.want)
			}
		})
	}
}

func TestAPipeReceivesTheExactDocument(t *testing.T) {
	f := installed(t)
	f.serve(t, completes(t))
	if got := f.run(t, "", nil, "query"); got.stdout != completedDocument+"\n" {
		t.Errorf("got %q", got.stdout)
	}
}

func TestAPersonsJvmCommandKeepsItsStatusAndRendersItsStreams(t *testing.T) {
	f := installed(t)
	var ran []string
	capture := func(_ context.Context, argv, _ []string, _ io.Reader, stdout, stderr io.Writer) (int, error) {
		ran = argv
		io.WriteString(stderr, `{"stage":"SHUTDOWN","outcome":"STARTED"}`+"\n")
		io.WriteString(stdout, `{"failure":"INVALID_COMMAND","cause":null}`+"\n")
		return 7, nil
	}
	got := f.person(t, nil, capture, nil, "workspace", "status")
	want := outcome{7, "failure: INVALID_COMMAND\ncause: none\n", "kast: stage: SHUTDOWN, outcome: STARTED\n"}
	if got != want {
		t.Errorf("got %+v want %+v", got, want)
	}
	if len(f.executed) != 0 || !slices.Equal(ran[len(ran)-2:], []string{"workspace", "status"}) {
		t.Errorf("executed %v, ran %v", f.executed, ran)
	}
}

func TestOnlyCommandsThatKeepTheInstallationRunAsAChild(t *testing.T) {
	unexpected := func(_ context.Context, argv, _ []string, _ io.Reader, _, _ io.Writer) (int, error) {
		t.Errorf("unexpected child %v", argv)
		return 0, nil
	}
	for _, arguments := range [][]string{
		{"upgrade"}, {"uninstall"}, {"hook", "session-start"}, {"--internal-entrypoint=mcp"},
		{"--internal-entrypoint=service", "run"}, {"status", "--json"},
	} {
		f := installed(t)
		f.person(t, nil, unexpected, nil, arguments...)
		if len(f.executed) != 1 {
			t.Errorf("%v was not executed", arguments)
		}
	}
}

func TestASignalEndsAChildAndOwnsTheStatus(t *testing.T) {
	f := installed(t)
	signals, started := make(chan os.Signal, 1), make(chan struct{})
	ended := make(chan struct{})
	capture := func(ctx context.Context, _, _ []string, _ io.Reader, _, _ io.Writer) (int, error) {
		close(started)
		<-ctx.Done()
		close(ended)
		return 1, nil
	}
	go func() {
		<-started
		signals <- syscall.SIGTERM
	}()
	if got := f.person(t, signals, capture, nil, "status"); got.status != terminatedStatus {
		t.Errorf("got %+v", got)
	}
	select {
	case <-ended:
	case <-time.After(time.Second):
		t.Error("the child was not cancelled")
	}
}

func TestANativeChildReportsItsExactStatusAndSignal(t *testing.T) {
	for script, want := range map[string]int{"exit 3": 3, "kill -TERM $$": 143, "exit 0": 0} {
		got, err := RunChild(context.Background(), []string{"/bin/sh", "-c", script}, nil, strings.NewReader(""), io.Discard,
			io.Discard)
		if err != nil || got != want {
			t.Errorf("%s: got %d, %v", script, got, err)
		}
	}
	if _, err := RunChild(context.Background(), []string{"/nonexistent"}, nil, nil, io.Discard, io.Discard); err == nil {
		t.Error("an absent program was reported as a status")
	}
}

func TestProgressFollowsTheSelectedWorkspace(t *testing.T) {
	for want, arguments := range map[string][]string{
		"/at":      {"query"},
		"/at/sub":  {"query", "--workspace", "sub"},
		"/else":    {"query", "--workspace=/else"},
		"/at/last": {"workspace", "wait", "--workspace", "last"},
	} {
		if got := workspaceDirectory("/at", arguments); got != want {
			t.Errorf("%v: got %q want %q", arguments, got, want)
		}
	}
}
