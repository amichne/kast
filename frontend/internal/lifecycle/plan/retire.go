package plan

import (
	"slices"

	"github.com/amichne/kast/frontend/internal/lifecycle/codec"
	"github.com/amichne/kast/frontend/internal/lifecycle/failure"
	"github.com/amichne/kast/frontend/internal/lifecycle/record"
)

// SupervisorState is one supervisor entry still present after its retirement, with its last state.
type SupervisorState struct {
	Label record.ServiceLabel
	State failure.UnitState
}

// SupervisorObservation is what the supervisor adapter reports: unavailable (no user bus or session), or available
// with the entries that remain. The zero value was never observed.
type SupervisorObservation struct {
	observed  bool
	available bool
	kind      failure.SupervisorKind
	remaining []SupervisorState
}

// NoSupervisor is a user without a service supervisor, which is a fact and not a failure.
func NoSupervisor() SupervisorObservation { return SupervisorObservation{observed: true} }

// SupervisorWith is an available supervisor and the entries that remain.
func SupervisorWith(kind failure.SupervisorKind, remaining ...SupervisorState) SupervisorObservation {
	return SupervisorObservation{true, true, kind, remaining}
}

func (s SupervisorObservation) isAvailable() bool {
	if !s.observed {
		panic("plan: the supervisor was not observed")
	}
	return s.available
}

// RetirementObservation is the observed result of running managed-retire: the child's exit status, whether its
// deadline expired, its stdout document, the supervisor's view of the document's labels, and the Host incarnations
// still alive.
type RetirementObservation struct {
	Exit       failure.ExitCode
	TimedOut   bool
	Document   []byte
	Supervisor SupervisorObservation
	Alive      []record.Incarnation
}

// ConfirmRetirement decides whether a retirement completed (section 1.1, step 5). A newer document is a downgrade
// (DOCUMENT_UNSUPPORTED); an undecodable document, or a RETIRED document from a failed child, is DOCUMENT_INVALID; a
// rejection maps its reason to REMOVAL_BLOCKED or RETIREMENT_REJECTED. Every label the document names must be
// absent from an available supervisor, and every Host incarnation it names must be gone.
func ConfirmRetirement(observed RetirementObservation) ([]failure.Observation, *failure.Failure) {
	stage := failure.StageRetire
	reject := func(rejected failure.Failure) ([]failure.Observation, *failure.Failure) { return nil, &rejected }
	if observed.TimedOut {
		return reject(failure.NewRetirementTimedOut(stage, observed.Exit, record.ReasonDeadlineExceeded))
	}
	document, err := record.DecodeRetirementDocument(observed.Document)
	if err != nil {
		reason := record.ReasonDocumentInvalid
		if cause, _ := codec.CauseOf(err); cause == codec.SchemaUnsupported {
			reason = record.ReasonDocumentUnsupported
		}
		return reject(failure.NewRetirementRejected(stage, observed.Exit, reason))
	}
	retired, ok := document.(record.Retired)
	if !ok {
		reason := document.(record.RetirementRejected).Reason()
		if reason.Removal() {
			return reject(failure.NewRemovalBlocked(failure.StageRemove, observed.Exit, reason))
		}
		return reject(failure.NewRetirementRejected(stage, observed.Exit, reason))
	}
	if observed.Exit.Value() != 0 {
		return reject(failure.NewRetirementRejected(stage, observed.Exit, record.ReasonDocumentInvalid))
	}
	var observations []failure.Observation
	if !observed.Supervisor.isAvailable() {
		observations = append(observations, failure.SupervisorUnavailable{})
	} else {
		labels := append([]record.ServiceLabel{retired.Semantic()}, retired.Brokers()...)
		for _, label := range labels {
			for _, remaining := range observed.Supervisor.remaining {
				if remaining.Label == label {
					return reject(failure.NewSupervisorStillActive(stage, observed.Supervisor.kind, label,
						remaining.State))
				}
			}
		}
	}
	for _, host := range retired.Hosts() {
		if slices.Contains(observed.Alive, host) {
			return reject(failure.NewProcessStillAlive(stage, host.PID, failure.UseExecutable))
		}
	}
	return observations, nil
}

// SupervisorEntry is one supervisor entry with the Kast label prefix, as list and show or print report it.
type SupervisorEntry struct {
	Label            string
	Program          string
	WorkingDirectory string
}

// LoginAgent is one login agent named by a broker label, and whether it matches its exact owned document.
type LoginAgent struct {
	Label   record.ServiceLabel
	Matches bool
}

// ForcedObservation is what forced retirement decides from, besides the process table.
type ForcedObservation struct {
	Supervisor  SupervisorObservation
	Entries     []SupervisorEntry
	LoginAgents []LoginAgent
}

// ForcedRetirement plans Go's retirement without a JVM (section 3.7): stop every owned supervisor entry and await
// its absence, terminate every process that uses one of the trees, and remove each login agent that matches its
// owned document. An entry is owned when its program or working directory lies inside R or a store; another agent
// stays and is reported.
func ForcedRetirement(paths Paths, trees []string, observed ForcedObservation, processes ProcessTable) ([]Step,
	[]failure.Observation) {
	var b builder
	var observations []failure.Observation
	owners := []string{paths.Root(record.RootR), paths.Root(record.RootRuntimeStore),
		paths.Root(record.RootBaseStore)}
	if !observed.Supervisor.isAvailable() {
		observations = append(observations, failure.SupervisorUnavailable{})
	} else {
		for _, entry := range observed.Entries {
			// An entry under the prefix that is not a semantic or broker label is not a shape Kast creates.
			label, ok := record.ParseServiceLabel(entry.Label)
			if !ok {
				continue
			}
			owned := false
			for _, owner := range owners {
				owned = owned || within(entry.Program, owner) || within(entry.WorkingDirectory, owner)
			}
			if owned {
				kind := observed.Supervisor.kind
				b.step("F1", StopSupervisorEntry{kind, label}, AwaitSupervisorAbsent{kind, label})
			}
		}
	}
	for _, process := range processes.others() {
		for _, tree := range trees {
			if _, uses := UseOf(process, tree); uses {
				b.step("F3", Terminate{process.Incarnation()})
				break
			}
		}
	}
	for _, agent := range observed.LoginAgents {
		if agent.Matches {
			b.step("F4", RemoveLoginAgent{agent.Label})
		} else {
			observations = append(observations, failure.LoginAgentRetained{Label: agent.Label})
		}
	}
	return b.steps, observations
}
