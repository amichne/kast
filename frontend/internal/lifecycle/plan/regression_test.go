package plan

import (
	"strings"
	"testing"

	"github.com/amichne/kast/frontend/internal/lifecycle/envelope"
	"github.com/amichne/kast/frontend/internal/lifecycle/failure"
	"github.com/amichne/kast/frontend/internal/lifecycle/layout"
	"github.com/amichne/kast/frontend/internal/lifecycle/record"
)

var newRuntimeHex = strings.Repeat("ab", 32)

// newRuntimeInput is an upgrade whose build binds another runtime entry, which A6 publishes before A9 runs P5.
func newRuntimeInput(t *testing.T) InstallInput {
	t.Helper()
	in := upgradeInput(t)
	next := []byte(strings.ReplaceAll(string(buildRecord(t)), runtimeHex, newRuntimeHex))
	in.Handoff, in.RecordBytes = handoff(t, envelope.OperationUpgrade, next), next
	in.Runtime = EntryMissing()
	relocked := withStaging(installedObservation(t), id(2))
	relocked.RuntimeStore = append(relocked.RuntimeStore, layout.Directory(newRuntimeHex, true))
	in.Relocked = observe(t, relocked)
	return in
}

// TestUpgradeKeepsTheEntryItJustPublished is F1 through the whole machine: A9 must not select the entry A6
// published, and A15 deletes only the entry the outgoing version bound.
func TestUpgradeKeepsTheEntryItJustPublished(t *testing.T) {
	got := Install(newRuntimeInput(t))
	expectOutcome(t, "new runtime", got, failure.Upgraded)
	var aside []string
	for _, intent := range got.Intents() {
		if rename, ok := intent.(RenameAside); ok {
			aside = append(aside, where(rename.From))
		}
	}
	if want := "R:versions/" + oldHex + " RUNTIME_STORE:" + runtimeHex; strings.Join(aside, " ") != want {
		t.Errorf("renamed aside %v, want %s", aside, want)
	}
}

// repairInput is a repair over a damaged runtime entry.
func repairInput(t *testing.T) InstallInput {
	t.Helper()
	in := upgradeInput(t)
	in.Operation = envelope.OperationRepair
	in.Handoff, in.RecordBytes = handoff(t, envelope.OperationRepair, buildRecord(t)), buildRecord(t)
	damage := failure.NewRuntimeEntryDamaged(failure.StageVerify, record.At(record.RootRuntimeStore, runtimeHex))
	in.Current.Runtime, in.Runtime = DamagedBy(damage), EntryPresent(DamagedBy(damage))
	return in
}

// damagedRepairInput is a repair over a current link whose version records are gone.
func damagedRepairInput(t *testing.T) InstallInput {
	t.Helper()
	in := repairInput(t)
	in.Runtime = EntryPresent(Verified())
	o := installedObservation(t)
	records := *o.Current
	records.Manifest = layout.Missing()
	o.Current = &records
	o.Versions = append(o.Versions, layout.Directory(id(99), true))
	in.Before, in.Locked, in.Relocked = observe(t, o), observe(t, o), observe(t, withStaging(o, id(2)))
	in.Forced = ForcedObservation{Supervisor: NoSupervisor()}
	return in
}

// TestRepairOverDamagedDeletesNothingBeforeTheSwitch: the bound set is unknown until current names the new version.
func TestRepairOverDamagedDeletesNothingBeforeTheSwitch(t *testing.T) {
	got := Install(damagedRepairInput(t))
	expectOutcome(t, "damaged repair", got, failure.Repaired)
	switched := false
	var aside []string
	for _, intent := range got.Intents() {
		switch effect := intent.(type) {
		case SwapCurrent:
			switched = true
		case RenameAside:
			if !switched {
				t.Errorf("%s was deleted before the switch", where(effect.From))
			}
			aside = append(aside, where(effect.From))
		}
	}
	if want := "R:versions/" + id(99) + " R:versions/" + oldHex; strings.Join(aside, " ") != want {
		t.Errorf("renamed aside %v, want %s", aside, want)
	}
}

// TestEachHoldDecidesFromItsOwnProcessTable: A9 and A15 read their own scans, and a use in either blocks a deletion.
func TestEachHoldDecidesFromItsOwnProcessTable(t *testing.T) {
	stray := installedObservation(t)
	stray.RuntimeStore = append(stray.RuntimeStore, layout.Directory(freshRuntimeHex, true))
	user := Process{PID: 71, WorkingDirectory: paths(t).Root(record.RootRuntimeStore) + "/" + freshRuntimeHex}
	nine := upgradeInput(t)
	nine.Relocked, nine.RelockedProcesses = observe(t, withStaging(stray, id(2))), table(user)
	got := Install(nine)
	expectFailure(t, "A9", got, failure.ProcessStillAlive)
	if last := got.Steps[len(got.Steps)-1]; last.Name != "A9" {
		t.Errorf("rejected at %s", last.Name)
	}
	final := upgradeInput(t)
	final.Final = table(Process{PID: 72, Arguments: []string{"-cp",
		paths(t).Root(record.RootR) + "/versions/" + oldHex + "/lib/a.jar"}})
	got = Install(final)
	expectFailure(t, "A15", got, failure.ProcessStillAlive)
	for _, intent := range got.Intents() {
		if rename, ok := intent.(RenameAside); ok {
			t.Errorf("%s was deleted while in use", where(rename.From))
		}
	}
}

// TestChangesBetweenTheHoldsFailClosed: nothing may change the installation between A4 and A8.
func TestChangesBetweenTheHoldsFailClosed(t *testing.T) {
	gone := installedObservation(t)
	gone.Root, gone.Current = gone.Root[1:], nil
	other := installedObservation(t)
	other.Root[0] = layout.Link("current", true, "versions/"+id(77))
	other.Versions = []layout.Entry{layout.Directory(id(77), true)}
	other.Current = versionRecords(t, id(77), buildRecord(t))
	for name, o := range map[string]layout.Observation{"current removed": gone, "current replaced": other} {
		in := upgradeInput(t)
		in.Relocked = observe(t, withStaging(o, id(2)))
		got := Install(in)
		expectFailure(t, name, got, failure.LayoutDamaged)
		if last := got.Steps[len(got.Steps)-1]; last.Name != "A9" {
			t.Errorf("%s: rejected at %s", name, last.Name)
		}
	}
}

func TestHandoffMustNameTheRecordedControl(t *testing.T) {
	in := upgradeInput(t)
	other, ok := envelope.NewHandoff(1, envelope.OperationUpgrade, in.Handoff.Selection(), in.Handoff.RecordSHA256(),
		digest(strings.Repeat("d", 64)), envelope.OutputText)
	if !ok {
		t.Fatal("handoff rejected")
	}
	in.Handoff = other
	expectFailure(t, "handoff control", Install(in), failure.ControlDigestMismatch)
}

// TestUpgradeWithNothingInstalledStopsAtAdmission: the only effects are the prelude's.
func TestUpgradeWithNothingInstalledStopsAtAdmission(t *testing.T) {
	for _, operation := range []envelope.Operation{envelope.OperationUpgrade, envelope.OperationRepair} {
		in := upgradeInput(t)
		in.Operation = operation
		in.Before, in.Locked = observe(t, layout.Observation{}), observe(t, layout.Observation{})
		got := Install(in)
		expectFailure(t, "nothing installed", got, failure.LayoutDamaged)
		expectSequence(t, "nothing installed", got, joined(lockBoth, releaseBoth))
	}
}

// TestUnobservedInputsAreDefectsNotSuccess: an observation nobody made must not read as "unused" or "verified".
func TestUnobservedInputsAreDefectsNotSuccess(t *testing.T) {
	cases := map[string]func(){
		"process table": func() { in := upgradeInput(t); in.Final = ProcessTable{}; Install(in) },
		"verification":  func() { in := upgradeInput(t); in.Current.Payload = Verification{}; Install(in) },
		"entry":         func() { in := upgradeInput(t); in.Runtime = EntryObservation{}; Install(in) },
		"supervisor": func() {
			in := upgradeInput(t)
			in.Retirement.Supervisor = SupervisorObservation{}
			Install(in)
		},
		"snapshot":    func() { in := freshInput(t); in.Locked = layout.Snapshot{}; Install(in) },
		"executables": func() { in := uninstallInput(t); in.Executables = Verification{}; Uninstall(in) },
		"commit scan": func() { in := uninstallInput(t); in.Committed = ProcessTable{}; Uninstall(in) },
	}
	for name, run := range cases {
		func() {
			defer func() {
				if recover() == nil {
					t.Errorf("%s: an unobserved input produced a plan", name)
				}
			}()
			run()
		}()
	}
}

// TestRepairProvesAReplacedEntryUnused is F2 for A11: an exchange replaces a runtime entry.
func TestRepairProvesAReplacedEntryUnused(t *testing.T) {
	in := repairInput(t)
	in.Final = table(Process{PID: 61, Executable: paths(t).Root(record.RootRuntimeStore) + "/" + runtimeHex + "/java"})
	got := Install(in)
	expectFailure(t, "used entry", got, failure.ProcessStillAlive)
	for _, intent := range got.Intents() {
		if _, exchanged := intent.(ExchangeEntry); exchanged {
			t.Error("an entry in use was exchanged")
		}
	}
}

// TestUnusualNamesNeverPanic: a file name is arbitrary bytes, and P0 must classify it, not crash.
func TestUnusualNamesNeverPanic(t *testing.T) {
	odd := "bad\xff\xfename"
	foreign := installedObservation(t)
	foreign.Root = append(foreign.Root, layout.File(odd, true))
	if state := layout.Classify(observe(t, foreign)).State(); state != layout.LayoutForeign {
		t.Errorf("state %v", state)
	}
	versions := installedObservation(t)
	versions.Versions = append(versions.Versions, layout.File(odd, true))
	if state := layout.Classify(observe(t, versions)).State(); state != layout.LayoutForeign {
		t.Errorf("state %v", state)
	}
	in := uninstallInput(t)
	bin := installedObservation(t)
	bin.Bin = append(bin.Bin, layout.File(odd, true))
	in.Before, in.Locked = observe(t, bin), observe(t, bin)
	expectOutcome(t, "odd name in the public command directory", Uninstall(in), failure.Uninstalled)
}
