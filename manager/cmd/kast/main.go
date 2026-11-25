// Command kast is the Kast Control executable: public queries go to the persistent coordinator, a reset, an
// installation and an agent connection run in this process, and every other entry replaces this process with the
// installation's JVM.
package main

import (
	"os"
	"os/signal"
	"syscall"

	"github.com/amichne/kast/frontend/internal/agent"
	"github.com/amichne/kast/frontend/internal/frontend"
	"github.com/amichne/kast/frontend/internal/install"
	"github.com/amichne/kast/frontend/internal/reset"
	"github.com/amichne/kast/frontend/internal/terminal"
)

func main() {
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)
	executable, err := os.Executable()
	if err != nil {
		executable = ""
	}
	// The kernel's working directory, not $PWD, matches the JVM's user.dir for the same process.
	directory, err := syscall.Getwd()
	if err != nil {
		directory = ""
	}
	os.Exit(frontend.Run(frontend.Process{
		Executable:  executable,
		Arguments:   os.Args[1:],
		Environment: os.Environ(),
		Directory:   directory,
		Stdin:       os.Stdin,
		Stdout:      os.Stdout,
		Stderr:      os.Stderr,
		Signals:     signals,
		Exec:        syscall.Exec,
		Capture:     frontend.RunChild,
		Terminal:    terminal.Is,
		Reset:       reset.Native,
		Uninstall:   reset.NativeUninstall,
		Upgrade:     install.NativeUpgrade,
		Install:     install.Native,
		Agent:       agent.Native,
	}))
}
