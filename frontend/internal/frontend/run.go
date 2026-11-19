package frontend

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"sync"
	"syscall"
)

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
	// Exec replaces this process with the JVM entry; it returns only on failure.
	Exec func(path string, argv []string, environment []string) error
}

// Run executes one invocation and returns the process status. The coordinator serves public queries; every other
// invocation replaces this process with the installation's JVM entry.
func Run(process Process) int {
	environment := environmentMap(process.Environment)
	installation, err := locateInstallation(process.Executable, environment)
	if err != nil {
		diagnose(process.Stderr, "the Kast installation is unavailable. Reinstall Kast.")
		return bootstrapStatus
	}
	catalog, err := LoadCatalog(installation)
	if err != nil {
		var failure *CatalogError
		errors.As(err, &failure)
		diagnose(process.Stderr, failure.Error())
		return failure.Status()
	}
	parsed := parseInvocation(process.Arguments)
	if !catalog.servedByCoordinator(parsed) {
		return execute(catalog, installation, parsed, process)
	}
	process.Arguments = parsed.arguments
	if text, ok := catalog.Help(process.Arguments, environment); ok {
		return deliver(process.Stdout, text, catalog.status("COMPLETE"), catalog)
	}
	owner := newOwner()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	// The invocation runs beside the signal wait. A signal owns the status at once, even while the invocation is
	// blocked on stdin or stdout; cancellation then closes the connection, which cancels the coordinator's request.
	finished := make(chan int, 1)
	go func() { finished <- complete(ctx, catalog, installation, parsed, process, environment, owner) }()
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

// execute replaces this process with the JVM entry. The process identity, stdio and environment pass through, so
// installation lifecycle owners observe the JVM exactly as they would a directly launched entry.
func execute(catalog *Catalog, installation string, parsed invocation, process Process) int {
	javaHome, err := catalog.runtimeHome(installation)
	if err != nil {
		diagnose(process.Stderr, "the Kast runtime is unavailable. Reinstall Kast.")
		return catalog.status("BOOTSTRAP")
	}
	command := catalog.jvmCommand(installation, javaHome, process.Executable, parsed)
	err = process.Exec(command[0], command, process.Environment)
	diagnose(process.Stderr, "the Kast runtime could not start: "+err.Error())
	return catalog.status("BOOTSTRAP")
}

// complete runs one invocation through delivery and records its status unless a signal already owns the result. A
// coordinator that is not reachable leaves the invocation to the JVM entry, which starts or joins one only when the
// command needs semantic evidence.
func complete(ctx context.Context, catalog *Catalog, installation string, parsed invocation, process Process,
	environment map[string]string, owner *owner) int {
	request := invocationRequest{
		Version:     supportedInvocationProtocol,
		Directory:   process.Directory,
		Arguments:   process.Arguments,
		Environment: environment,
	}
	conn, err := dial(ctx, catalog.socket, catalog.socketAlias, catalog.handshake)
	if isUnreachable(err) {
		if status, settled := owner.terminal(); settled {
			return status
		}
		return owner.finish(execute(catalog, installation, parsed, process))
	}
	if err != nil {
		return owner.finish(failureStatus(catalog, err, process.Stderr))
	}
	completed, err := exchange(ctx, conn, request, process.Stdin)
	conn.CloseNow()
	if status, settled := owner.terminal(); settled {
		return status
	}
	if err != nil {
		return owner.finish(failureStatus(catalog, err, process.Stderr))
	}
	if environment["KAST_FRONTEND_TRACE"] == "1" {
		traceInvocation(process.Stderr, completed.Coordinator)
	}
	for _, line := range completed.Diagnostics {
		fmt.Fprintln(process.Stderr, line)
	}
	return owner.finish(deliver(process.Stdout, completed.Output, completed.ExitCode, catalog))
}

func failureStatus(catalog *Catalog, err error, stderr io.Writer) int {
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
func traceInvocation(stderr io.Writer, served coordinator) {
	encoded, _ := json.Marshal(struct {
		Type              string `json:"type"`
		CallerProcessID   int    `json:"callerProcessId"`
		ServiceGeneration string `json:"serviceGeneration"`
		ProcessID         int64  `json:"processId"`
	}{"frontend_trace", os.Getpid(), served.ServiceGeneration, served.ProcessID})
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
