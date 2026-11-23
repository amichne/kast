package plan

import (
	"strings"
	"testing"

	"github.com/amichne/kast/frontend/internal/lifecycle/failure"
	"github.com/amichne/kast/frontend/internal/lifecycle/record"
)

func TestConfirmRetirementChecksEveryFactOfTheDocument(t *testing.T) {
	label := must(record.ParseServiceLabel(semantic))
	cases := map[string]struct {
		change func(*RetirementObservation)
		want   failure.Code
		reason record.RetirementReason
	}{
		"deadline": {func(o *RetirementObservation) {
			o.TimedOut, o.Exit = true, must(failure.ParseExitCode(-1))
		}, failure.RetirementTimedOut, record.ReasonDeadlineExceeded},
		"newer document": {func(o *RetirementObservation) {
			o.Document = []byte(`{"schemaVersion":2,"outcome":"RETIRED"}`)
		}, failure.RetirementRejected, record.ReasonDocumentUnsupported},
		"no document": {func(o *RetirementObservation) { o.Document = nil }, failure.RetirementRejected,
			record.ReasonDocumentInvalid},
		"retired from a failed child": {func(o *RetirementObservation) { o.Exit = must(failure.ParseExitCode(1)) },
			failure.RetirementRejected, record.ReasonDocumentInvalid},
		"foreign login agent": {func(o *RetirementObservation) {
			o.Document = []byte(`{"schemaVersion":1,"outcome":"REJECTED","reason":"LOGIN_AGENT_FOREIGN"}`)
		}, failure.RetirementRejected, record.ReasonLoginAgentForeign},
		"active host blocks removal": {func(o *RetirementObservation) {
			o.Document = []byte(`{"schemaVersion":1,"outcome":"REJECTED","reason":"ACTIVE_HOST"}`)
		}, failure.RemovalBlocked, record.ReasonActiveHost},
	}
	for name, c := range cases {
		observed := cleanRetirement()
		c.change(&observed)
		_, rejected := ConfirmRetirement(observed)
		if rejected == nil || rejected.Code() != c.want || rejected.Evidence().(failure.ChildEvidence).Reason != c.reason {
			t.Errorf("%s: %v", name, rejected)
		}
	}
	remaining := cleanRetirement()
	remaining.Supervisor = SupervisorWith(failure.SupervisorSystemd, SupervisorState{label, failure.StateDeactivating})
	if _, rejected := ConfirmRetirement(remaining); rejected == nil || rejected.Code() != failure.SupervisorStillActive {
		t.Errorf("a deactivating unit passed: %v", rejected)
	}
	alive := cleanRetirement()
	alive.Alive = []record.Incarnation{{PID: 4100, StartTime: 5}}
	if _, rejected := ConfirmRetirement(alive); rejected == nil || rejected.Code() != failure.ProcessStillAlive {
		t.Errorf("a live Host incarnation passed: %v", rejected)
	}
	reused := cleanRetirement()
	reused.Alive = []record.Incarnation{{PID: 4100, StartTime: 6}}
	if _, rejected := ConfirmRetirement(reused); rejected != nil {
		t.Errorf("a reused process identifier blocked retirement: %v", rejected)
	}
	unavailable := cleanRetirement()
	unavailable.Supervisor = NoSupervisor()
	observations, rejected := ConfirmRetirement(unavailable)
	if rejected != nil || len(observations) != 1 || observations[0] != (failure.SupervisorUnavailable{}) {
		t.Errorf("no supervisor: %v %v", observations, rejected)
	}
}

func TestForcedRetirementStopsOnlyWhatKastOwns(t *testing.T) {
	p := paths(t)
	version := p.Root(record.RootR) + "/versions/" + oldHex
	steps, observations := ForcedRetirement(p, []string{version}, ForcedObservation{
		Supervisor: SupervisorWith(failure.SupervisorSystemd),
		Entries: []SupervisorEntry{
			{Label: semantic, Program: version + "/bin/kast"},
			{Label: "io.github.amichne.kast.broker." + strings.Repeat("4", 32), WorkingDirectory: "/srv"},
			{Label: "io.github.amichne.kast.unknown", Program: version + "/bin/kast"},
		},
	}, table(
		Process{PID: 1, StartTime: 1, Executable: version + "/bin/kast"},
		Process{PID: 2, StartTime: 1, Executable: "/usr/bin/vim", Arguments: []string{"/home/user/notes"}},
		Process{PID: self, StartTime: 1, Executable: version + "/bin/kast"},
	))
	if got := strings.Join(strings_((Plan{Steps: steps}).Intents()), "\n"); got != strings.Join([]string{
		"stop SYSTEMD " + semantic, "await-absent SYSTEMD " + semantic, "terminate 1@1"}, "\n") {
		t.Errorf("forced:\n%s", got)
	}
	if len(observations) != 0 {
		t.Errorf("observations %+v", observations)
	}
	_, unavailable := ForcedRetirement(p, nil, ForcedObservation{Supervisor: NoSupervisor()}, table())
	if len(unavailable) != 1 || unavailable[0] != (failure.SupervisorUnavailable{}) {
		t.Errorf("no supervisor: %+v", unavailable)
	}
}
