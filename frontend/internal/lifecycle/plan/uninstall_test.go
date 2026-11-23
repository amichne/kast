package plan

import (
	"strings"
	"testing"

	"github.com/amichne/kast/frontend/internal/lifecycle/failure"
	"github.com/amichne/kast/frontend/internal/lifecycle/layout"
	"github.com/amichne/kast/frontend/internal/lifecycle/record"
)

func uninstallInput(t *testing.T) UninstallInput {
	t.Helper()
	installed := observe(t, installedObservation(t))
	retirement := cleanRetirement()
	return UninstallInput{Paths: paths(t), IDs: sequence{}, Home: healthyHome(), Before: installed, Locked: installed,
		Retirement: retirement}
}

var residueOfUninstall = []string{
	"prove-unused HOME_SHARE:kast-workspaces", "prove-unused HOME_SHARE:kast-runtime-bases", "lock-store-lifetime",
	"rename-aside HOME_SHARE:kast-workspaces .kast-removing-" + id(2),
	"rename-aside HOME_SHARE:kast-runtime-bases .kast-removing-" + id(3), "unlock-store-lifetime",
	"unlink PUBLIC_COMMAND:kast", "delete .kast-removing-" + id(2), "delete .kast-removing-" + id(3),
	"prove-unused HOME_SHARE:.kast-uninstalled-" + id(1), "empty HOME_SHARE:.kast-uninstalled-" + id(1),
	"rmdir HOME_SHARE:.kast-uninstalled-" + id(1),
}

var commit = []string{
	"prove-unused R:versions/" + oldHex, "prove-unused RUNTIME_STORE:" + runtimeHex,
	"prove-unused BASE_STORE:" + baseHex, "rename-root .kast-uninstalled-" + id(1), "sync-dir HOME_SHARE:.",
}

func TestUninstallIntentSequence(t *testing.T) {
	got := Uninstall(uninstallInput(t))
	expectOutcome(t, "uninstall", got, failure.Uninstalled)
	expectSequence(t, "uninstall", got, joined(lockBoth,
		[]string{"verify-version " + oldHex, "verify-entry RUNTIME_STORE:" + runtimeHex, "retire " + oldHex + " removal"},
		commit, residueOfUninstall, releaseBoth))
	steps := map[StepName]int{}
	for i, step := range got.Steps {
		steps[step.Name] = i
	}
	if !(steps["X2"] < steps["X3"] && steps["X3"] < steps["R1"] && steps["R4"] < steps["X5"]) {
		t.Errorf("uninstall rows out of order: %+v", got.Steps)
	}
}

func TestForcedUninstallOverDamagedSkipsKotlin(t *testing.T) {
	p := paths(t)
	in := uninstallInput(t)
	in.Force = true
	damaged := installedObservation(t)
	records := *damaged.Current
	records.Selection = layout.Missing()
	damaged.Current = &records
	in.Before, in.Locked = observe(t, damaged), observe(t, damaged)
	host := Process{PID: 31, StartTime: 2, Executable: p.Root(record.RootRuntimeStore) + "/" + runtimeHex + "/host/java"}
	in.Processes = []Process{host}
	broker := must(record.ParseServiceLabel("io.github.amichne.kast.broker." + strings.Repeat("2", 32)))
	foreignAgent := must(record.ParseServiceLabel("io.github.amichne.kast.broker." + strings.Repeat("3", 32)))
	in.Forced = ForcedObservation{
		Supervisor:  SupervisorObservation{Available: true, Kind: failure.SupervisorLaunchd},
		Entries:     []SupervisorEntry{{Label: semantic, WorkingDirectory: p.Root(record.RootR)}},
		LoginAgents: []LoginAgent{{Label: broker, Matches: true}, {Label: foreignAgent, Matches: false}},
	}
	got := Uninstall(in)
	expectOutcome(t, "forced", got, failure.UninstalledWithoutUserState)
	expectSequence(t, "forced", got, joined(lockBoth,
		[]string{"stop LAUNCHD " + semantic, "await-absent LAUNCHD " + semantic, "terminate 31@2",
			"remove-login-agent " + broker.String()},
		commit, residueOfUninstall, releaseBoth))
	observations := got.Result.Outcome.Observations()
	if len(observations) != 1 || observations[0] != (failure.LoginAgentRetained{Label: foreignAgent}) {
		t.Errorf("observations %+v", observations)
	}
	in.Force = false
	expectFailure(t, "damaged without force", Uninstall(in), failure.LayoutDamaged)
}

func TestUninstallBlocksOnALiveProcessAtTheCommit(t *testing.T) {
	in := uninstallInput(t)
	in.Committed = []Process{{PID: 41, Arguments: []string{"-cp", paths(t).Root(record.RootR) + "/versions/" + oldHex + "/lib/a.jar"}}}
	got := Uninstall(in)
	expectFailure(t, "live process", got, failure.ProcessStillAlive)
	for _, intent := range got.Intents() {
		if _, renamed := intent.(RenameRoot); renamed {
			t.Fatal("R was renamed while a process used a version")
		}
	}
}

func TestUninstallMapsKotlinRemovalChecks(t *testing.T) {
	in := uninstallInput(t)
	in.Retirement.Document = []byte(`{"schemaVersion":1,"outcome":"REJECTED","reason":"UNSETTLED_MUTATION"}`)
	in.Retirement.Exit = must(failure.ParseExitCode(2))
	got := Uninstall(in)
	expectFailure(t, "removal blocked", got, failure.RemovalBlocked)
	if got.Result.Failure.Evidence().(failure.ChildEvidence).Reason != record.ReasonUnsettledMutation {
		t.Errorf("reason %+v", got.Result.Failure.Evidence())
	}
	damaged := failure.NewRetirementExecutableDamaged(failure.StageVerify,
		record.At(record.RootR, "versions/"+oldHex+"/share/kast/libexec/kast-management"))
	in.Current.Retirement = &damaged
	expectFailure(t, "damaged retirement executables", Uninstall(in), failure.RetirementExecutableDamaged)
}

func TestUninstallFinishesAResidueAlone(t *testing.T) {
	in := uninstallInput(t)
	witness := layout.Observation{Share: []layout.Entry{layout.Directory(".kast-uninstalled-"+id(9), true)}}
	in.Before, in.Locked = observe(t, witness), observe(t, witness)
	got := Uninstall(in)
	expectOutcome(t, "residue only", got, failure.Uninstalled)
	expectSequence(t, "residue only", got, joined(lockBoth,
		[]string{"prove-unused HOME_SHARE:.kast-uninstalled-" + id(9), "empty HOME_SHARE:.kast-uninstalled-" + id(9),
			"rmdir HOME_SHARE:.kast-uninstalled-" + id(9)}, releaseBoth))
	in.Before, in.Locked = observe(t, layout.Observation{}), observe(t, layout.Observation{})
	expectFailure(t, "nothing installed", Uninstall(in), failure.LayoutDamaged)
}
