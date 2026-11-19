package frontend

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
)

// maximumBootstrapOutputBytes bounds what a failed start relays to stderr.
const maximumBootstrapOutputBytes = 16 << 10

// Process is the caller's process state, passed explicitly so tests control every effect.
type Process struct {
	Executable  string
	Arguments   []string
	Environment []string
	Directory   string
	Stdin       io.Reader
	Stdout      io.Writer
	Stderr      io.Writer
	// Signals delivers SIGINT or SIGTERM; the first terminal event owns the status.
	Signals <-chan os.Signal
}

// Run executes one invocation and returns the process status.
func Run(process Process) int {
	root, err := locateRoot(process.Executable)
	if err != nil {
		diagnose(process.Stderr, "the Kast installation root is unavailable. Reinstall Kast.")
		return bootstrapStatus
	}
	catalog, err := LoadCatalog(root)
	if err != nil {
		var failure *CatalogError
		errors.As(err, &failure)
		diagnose(process.Stderr, failure.Error())
		return failure.Status()
	}
	environment := environmentMap(process.Environment)
	if text, ok := catalog.Help(process.Arguments, environment); ok {
		return deliver(process.Stdout, text, catalog.status("COMPLETE"), catalog)
	}
	owner := newOwner()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	// The invocation runs beside the signal wait. A signal owns the status at once, even while the invocation is
	// blocked on stdin or stdout; cancellation then closes the connection, which cancels the coordinator's request.
	finished := make(chan int, 1)
	go func() { finished <- complete(ctx, catalog, process, environment, owner) }()
	select {
	case status := <-finished:
		return status
	case signal := <-process.Signals:
		status := catalog.status("INTERRUPTED")
		if signal == syscall.SIGTERM {
			status = catalog.status("TERMINATED")
		}
		return owner.finish(status)
	}
}

// complete runs one invocation through delivery and records its status unless a signal already owns the result.
func complete(ctx context.Context, catalog *Catalog, process Process, environment map[string]string, owner *owner) int {
	request := invocationRequest{
		Version:     supportedInvocationProtocol,
		Directory:   process.Directory,
		Arguments:   process.Arguments,
		Environment: environment,
	}
	completed, bootstrapped, err := invoke(ctx, catalog, request, process)
	if status, settled := owner.terminal(); settled {
		return status
	}
	if err != nil {
		return owner.finish(failureStatus(catalog, err, process.Stderr))
	}
	if environment["KAST_FRONTEND_TRACE"] == "1" {
		traceInvocation(process.Stderr, completed.Coordinator, bootstrapped)
	}
	for _, line := range completed.Diagnostics {
		fmt.Fprintln(process.Stderr, line)
	}
	return owner.finish(deliver(process.Stdout, completed.Output, completed.ExitCode, catalog))
}

// invoke connects to the published coordinator, starting or joining it once when no coordinator is reachable.
func invoke(ctx context.Context, catalog *Catalog, request invocationRequest, process Process) (*Completed, bool, error) {
	conn, err := dial(ctx, catalog.socket, catalog.handshake)
	bootstrapped := false
	if isUnreachable(err) {
		if startErr := startCoordinator(ctx, catalog, process); startErr != nil {
			return nil, true, startErr
		}
		bootstrapped = true
		conn, err = dial(ctx, catalog.socket, catalog.handshake)
	}
	if err != nil {
		if bootstrapped {
			return nil, true, errBootstrap
		}
		return nil, false, err
	}
	defer conn.CloseNow()
	completed, err := exchange(ctx, conn, request, process.Stdin)
	return completed, bootstrapped, err
}

var errBootstrap = errors.New("bootstrap failed")

// startCoordinator runs the catalog's start-or-join command. The coordinator's start lock admits one owner.
func startCoordinator(ctx context.Context, catalog *Catalog, process Process) error {
	command := exec.CommandContext(ctx, catalog.bootstrap[0], catalog.bootstrap[1:]...)
	command.Env = process.Environment
	command.Dir = catalog.root
	var stderr bytes.Buffer
	command.Stdout = io.Discard
	command.Stderr = &limitedBuffer{buffer: &stderr, remaining: maximumBootstrapOutputBytes}
	if err := command.Run(); err != nil {
		if relayed := strings.TrimSpace(stderr.String()); relayed != "" {
			fmt.Fprintln(process.Stderr, relayed)
		}
		return errBootstrap
	}
	return nil
}

func failureStatus(catalog *Catalog, err error, stderr io.Writer) int {
	if errors.Is(err, errBootstrap) {
		diagnose(stderr, "the coordinator could not be started or joined.")
		return catalog.status("BOOTSTRAP")
	}
	var transport *TransportError
	if !errors.As(err, &transport) {
		diagnose(stderr, "the frontend failed.")
		return catalog.status("DEFECT")
	}
	diagnose(stderr, transport.Error())
	switch transport.Failure {
	case Rejected:
		switch transport.Reason {
		case "CAPACITY_EXCEEDED", "INPUT_EXPIRED":
			return catalog.status("INPUT")
		case "LIFECYCLE_TRANSITION", "UNAVAILABLE":
			return catalog.status("RUNTIME")
		default:
			return catalog.status("PROTOCOL")
		}
	case InputUnreadable:
		return catalog.status("IO")
	case Unreachable:
		return catalog.status("BOOTSTRAP")
	default:
		return catalog.status("RUNTIME")
	}
}

// deliver writes the presented document as the public shell does: the text and one trailing newline. The write is
// unbuffered, so its completion is delivery; a failed or short write is an IO status.
func deliver(stdout io.Writer, text string, status int, catalog *Catalog) int {
	if _, err := io.WriteString(stdout, text+"\n"); err != nil {
		return catalog.status("IO")
	}
	return status
}

// locateRoot resolves bin/kast to the coordinator root.
func locateRoot(executable string) (string, error) {
	resolved, err := filepath.EvalSymlinks(executable)
	if err != nil {
		return "", err
	}
	bin := filepath.Dir(resolved)
	if filepath.Base(bin) != "bin" {
		return "", errors.New("frontend is not beneath bin")
	}
	return filepath.Dir(bin), nil
}

func environmentMap(environment []string) map[string]string {
	selected := make(map[string]string, len(environment))
	for _, entry := range environment {
		if key, value, ok := strings.Cut(entry, "="); ok && key != "" {
			selected[key] = value
		}
	}
	return selected
}

func diagnose(stderr io.Writer, message string) { fmt.Fprintln(stderr, "kast: "+message) }

// traceInvocation records which coordinator served this invocation, so warm reuse can be proven per call.
func traceInvocation(stderr io.Writer, served coordinator, bootstrapped bool) {
	encoded, _ := json.Marshal(struct {
		Type              string `json:"type"`
		ServiceGeneration string `json:"serviceGeneration"`
		ProcessID         int64  `json:"processId"`
		Bootstrapped      bool   `json:"bootstrapped"`
	}{"frontend_trace", served.ServiceGeneration, served.ProcessID, bootstrapped})
	fmt.Fprintln(stderr, string(encoded))
}

// owner accepts the first terminal event, as the public shell's invocation owner does: a signal that arrives before or
// during delivery owns the status.
type owner struct {
	mutex   sync.Mutex
	settled bool
	status  int
}

func newOwner() *owner { return &owner{} }

func (o *owner) settle(status int) bool {
	o.mutex.Lock()
	defer o.mutex.Unlock()
	if o.settled {
		return false
	}
	o.settled, o.status = true, status
	return true
}

func (o *owner) terminal() (int, bool) {
	o.mutex.Lock()
	defer o.mutex.Unlock()
	return o.status, o.settled
}

// finish records status unless an earlier terminal event already owns the result, and returns the owned status.
func (o *owner) finish(status int) int {
	o.settle(status)
	owned, _ := o.terminal()
	return owned
}

type limitedBuffer struct {
	buffer    *bytes.Buffer
	remaining int
}

func (b *limitedBuffer) Write(p []byte) (int, error) {
	if b.remaining > 0 {
		kept := p[:min(len(p), b.remaining)]
		b.buffer.Write(kept)
		b.remaining -= len(kept)
	}
	return len(p), nil
}
