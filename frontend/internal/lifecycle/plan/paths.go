// Package plan expresses each lifecycle machine of section 3 as pure functions from typed observations to an
// ordered sequence of typed effect intents, ending in an outcome or a failure. No effect executes here: a plan is
// data that a future interpreter performs one intent at a time.
package plan

import (
	"crypto/sha256"
	"encoding/binary"
	"path"
	"strings"

	"github.com/amichne/kast/frontend/internal/lifecycle/failure"
	"github.com/amichne/kast/frontend/internal/lifecycle/layout"
	"github.com/amichne/kast/frontend/internal/lifecycle/record"
)

// Paths are the absolute locations every root names, derived from one admitted home directory. They are used only
// to compare with observed absolute paths and to spell the public command's fixed target; no evidence carries them.
type Paths struct{ home string }

// AdmitHome admits a clean absolute home directory other than the file system root (HOME_INVALID otherwise).
func AdmitHome(home string) (Paths, *failure.Failure) {
	if len(home) < 2 || !strings.HasPrefix(home, "/") || path.Clean(home) != home || strings.ContainsRune(home, 0) {
		rejected := failure.NewHomeInvalid(failure.StagePrecheck)
		return Paths{}, &rejected
	}
	return Paths{home}, nil
}

// Root is the absolute directory a root kind names.
func (p Paths) Root(root record.RootKind) string {
	share := p.home + "/.local/share"
	switch root {
	case record.RootHome:
		return p.home
	case record.RootHomeShare:
		return share
	case record.RootR:
		return share + "/" + layout.RootName
	case record.RootRuntimeStore:
		return share + "/" + layout.WorkspaceStoreName + "/" + layout.RuntimesName
	case record.RootBaseStore:
		return share + "/" + layout.BaseStoreName
	case record.RootPublicCommand:
		return p.home + "/.local/bin"
	case record.RootTmpAlias:
		return "/tmp"
	default:
		panic("plan: the handoff directory has no fixed absolute path")
	}
}

// Absolute is a location's absolute path; a location with an empty path is its root directory.
func (p Paths) Absolute(location record.Location) string {
	if location.Path.String() == "" {
		return p.Root(location.Root)
	}
	return p.Root(location.Root) + "/" + location.Path.String()
}

// PublicTarget is the fixed target string of ~/.local/bin/kast.
func (p Paths) PublicTarget() string {
	return p.Root(record.RootHomeShare) + "/" + layout.PublicCommandTargetTip
}

// Process is one process of the user as the process-use scan observed it, with absolute physical paths.
type Process struct {
	PID              record.ProcessID
	StartTime        int64
	Executable       string
	Arguments        []string
	WorkingDirectory string
}

// Incarnation is the process's identity.
func (p Process) Incarnation() record.Incarnation {
	return record.Incarnation{PID: p.PID, StartTime: p.StartTime}
}

func within(candidate, tree string) bool {
	return candidate == tree || strings.HasPrefix(candidate, tree+"/")
}

// UseOf reports how a process uses a tree: its executable or working directory lies inside it, or any argument
// mentions it. An argument counts wherever it names the tree, as an element of a class path or after "=", because
// a JVM names V/lib only inside -cp. The match is a plain substring, so it can only report too many uses.
func UseOf(process Process, tree string) (failure.ProcessUse, bool) {
	switch {
	case within(process.Executable, tree):
		return failure.UseExecutable, true
	case within(process.WorkingDirectory, tree):
		return failure.UseWorkingDirectory, true
	}
	for _, argument := range process.Arguments {
		if strings.Contains(argument, tree) {
			return failure.UseArgument, true
		}
	}
	return 0, false
}

// ProcessTable is one completed scan of the user's processes, without the scanning command itself: an installed
// command runs from the version it removes, and forced retirement must never terminate its own process. The zero
// table was never observed, so it proves nothing and is a defect wherever a proof is needed.
type ProcessTable struct {
	observed  bool
	processes []Process
}

// ObserveProcesses admits a scan and drops the command's own process.
func ObserveProcesses(self record.ProcessID, processes []Process) ProcessTable {
	table := ProcessTable{observed: true}
	for _, process := range processes {
		if process.PID != self {
			table.processes = append(table.processes, process)
		}
	}
	return table
}

func (t ProcessTable) others() []Process {
	if !t.observed {
		panic("plan: the process table was not observed")
	}
	return t.processes
}

// firstUse finds the first process, in table order, that uses any of the trees.
func firstUse(table ProcessTable, stage failure.Stage, trees []string) (*failure.Failure, bool) {
	for _, process := range table.others() {
		for _, tree := range trees {
			if use, ok := UseOf(process, tree); ok {
				rejected := failure.NewProcessStillAlive(stage, process.PID, use)
				return &rejected, true
			}
		}
	}
	return nil, false
}

// IDSource supplies the random identifiers a command names its trees with. Randomness is an effect, so the
// interpreter provides it; the plan only asks for the n-th identifier.
type IDSource interface {
	ID(n int) record.VersionID
}

// Entropy derives distinct identifiers from 32 random bytes the interpreter drew once.
type Entropy [32]byte

// ID is the first 8 bytes of SHA-256 over the entropy and the index.
func (e Entropy) ID(n int) record.VersionID {
	var input [40]byte
	copy(input[:], e[:])
	binary.BigEndian.PutUint64(input[32:], uint64(n))
	sum := sha256.Sum256(input[:])
	var id record.VersionID
	copy(id[:], sum[:8])
	return id
}

// fresh hands out identifiers in order.
type fresh struct {
	source IDSource
	next   int
}

func (f *fresh) id() record.VersionID {
	id := f.source.ID(f.next)
	f.next++
	return id
}
