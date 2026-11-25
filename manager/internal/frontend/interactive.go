package frontend

import (
	"context"
	"errors"
	"io"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"syscall"
	"time"

	"github.com/amichne/kast/frontend/internal/terminal"
)

// childGrace bounds the wait for a cancelled JVM entry to end.
const childGrace = 5 * time.Second

// Capture runs the JVM entry as a child and returns its status.
type Capture func(ctx context.Context, argv, environment []string, stdin io.Reader, stdout, stderr io.Writer) (int, error)

// RunChild is the native Capture.
func RunChild(ctx context.Context, argv, environment []string, stdin io.Reader, stdout, stderr io.Writer) (int, error) {
	command := exec.CommandContext(ctx, argv[0], argv[1:]...)
	command.Env, command.Stdin, command.Stdout, command.Stderr = environment, stdin, stdout, stderr
	command.Cancel = func() error { return command.Process.Signal(syscall.SIGTERM) }
	command.WaitDelay = childGrace
	err := command.Run()
	var exited *exec.ExitError
	switch {
	case errors.As(err, &exited) && exited.Exited():
		return exited.ExitCode(), nil
	case errors.As(err, &exited):
		return 128 + int(exited.Sys().(syscall.WaitStatus).Signal()), nil
	default:
		return 0, err
	}
}

// presented reports whether a person reads this invocation's result. A caller that names a format gets that format.
func (c *Catalog) presented(process Process, arguments []string, environment map[string]string) bool {
	format := environment[c.outputSetting.Variable]
	return process.Terminal != nil && process.Terminal(process.Stdout) && format == "" &&
		!slices.ContainsFunc(arguments, namesFormat)
}

// namesFormat reports a selector of machine output. A projection is JSON that the caller asked for.
func namesFormat(argument string) bool {
	return argument == "--json" || argument == "--jq" || strings.HasPrefix(argument, "--json=") ||
		strings.HasPrefix(argument, "--jq=")
}

// childServed names the management commands that may run as a child. A command that replaces or removes the
// installation keeps the process that its lifecycle owners observe.
var childServed = []string{"status", "stop", "versions", "config", "install", "workspace"}

func (c *Catalog) runsAsChild(parsed invocation) bool {
	if parsed.entry != "management" {
		return false
	}
	if len(parsed.arguments) == 0 {
		return true
	}
	return slices.Contains(childServed, parsed.arguments[0]) ||
		slices.Contains(c.publicNamespaces, parsed.arguments[0]) && parsed.arguments[0] != "hook"
}

// follow shows preparation progress on a terminal until the returned function runs.
func follow(ctx context.Context, process Process, environment map[string]string) func() {
	if process.Terminal == nil || !process.Terminal(process.Stderr) || !filepath.IsAbs(environment["HOME"]) {
		return func() {}
	}
	ctx, cancel := context.WithCancel(ctx)
	ended := make(chan struct{})
	progress := terminal.Progress{
		Store:     filepath.Join(environment["HOME"], ".local", "share", "kast-workspaces"),
		Directory: workspaceDirectory(process.Directory, process.Arguments),
		Alive:     terminal.Alive,
	}
	go func() {
		defer close(ended)
		progress.Follow(ctx, process.Stderr)
	}()
	return func() {
		cancel()
		<-ended
	}
}

// workspaceDirectory is where the selected workspace starts. It only chooses what progress to show.
func workspaceDirectory(directory string, arguments []string) string {
	for index, argument := range arguments {
		selected, inline := strings.CutPrefix(argument, "--workspace=")
		if argument == "--workspace" && index+1 < len(arguments) {
			selected, inline = arguments[index+1], true
		}
		if inline && filepath.IsAbs(selected) {
			return selected
		}
		if inline {
			return filepath.Join(directory, selected)
		}
	}
	return directory
}

// presentChild runs the JVM entry as a child and renders its documents. Progress follows only the commands that can
// prepare a workspace.
func presentChild(ctx context.Context, argv []string, parsed invocation, process Process,
	environment map[string]string) (int, error) {
	var stdout strings.Builder
	stderr := &terminal.Lines{Out: process.Stderr}
	done := func() {}
	if len(parsed.arguments) > 0 && (parsed.arguments[0] == "workspace" || parsed.arguments[0] == "query") {
		done = follow(ctx, process, environment)
	}
	status, err := process.Capture(ctx, argv, process.Environment, process.Stdin, &stdout, stderr)
	done()
	if err != nil {
		return 0, err
	}
	if err := stderr.Flush(); err != nil {
		return 0, err
	}
	if stdout.Len() > 0 {
		_, err = io.WriteString(process.Stdout, terminal.Document(strings.TrimRight(stdout.String(), "\n"))+"\n")
	}
	return status, err
}
