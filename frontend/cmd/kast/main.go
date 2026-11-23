// Command kast is the Kast Control executable: public queries go to the persistent coordinator, a reset and an
// installation run in this process, and every other entry replaces this process with the installation's JVM.
package main

import (
	"os"
	"os/signal"
	"syscall"

	"github.com/amichne/kast/frontend/internal/frontend"
	"github.com/amichne/kast/frontend/internal/install"
	"github.com/amichne/kast/frontend/internal/reset"
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
		Reset:       reset.Native,
		Install:     install.Native,
	}))
}
