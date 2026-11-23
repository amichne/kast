package plan

import (
	"strings"
	"testing"

	"github.com/amichne/kast/frontend/internal/lifecycle/failure"
	"github.com/amichne/kast/frontend/internal/lifecycle/layout"
	"github.com/amichne/kast/frontend/internal/lifecycle/record"
)

const freshRuntimeHex = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

func runCleanup(t *testing.T, o layout.Observation, bound BoundSet, own Own, processes []Process) (Plan, *failure.Failure) {
	t.Helper()
	snapshot := observe(t, o)
	var b builder
	rejected := cleanup(&b, "P5", cleanupInput{paths(t), snapshot, layout.Classify(snapshot), bound, own,
		table(processes...), &fresh{source: sequence{}}})
	return Plan{Steps: b.steps}, rejected
}

// TestFreshlyPublishedEntryIsNeverSelected is F1: at A9, the own staging tree binds an entry that A6 just published,
// and current binds another. Neither is a cleanup candidate; an entry neither binds is.
func TestFreshlyPublishedEntryIsNeverSelected(t *testing.T) {
	o := installedObservation(t)
	o.Root = append(o.Root, layout.Directory(".staging-"+id(9), true))
	o.RuntimeStore = append(o.RuntimeStore, layout.Directory(freshRuntimeHex, true),
		layout.Directory(strings.Repeat("b", 64), true))
	snapshot := observe(t, o)
	classification := layout.Classify(snapshot)
	current := classification.(layout.Current).Installed.Selection
	ownID := must(record.ParseVersionID(id(9)))
	ownSelection := record.NewProductSelection(must(record.ParseVersionID(id(8))), record.TargetLinuxX8664,
		current.RecordSHA256(), previewIdentity(), current.SourceRevision(), digest(freshRuntimeHex),
		current.RuntimeManifest(), nil)
	own := Own{Staging: &ownID}

	candidates := SelectForCleanup(snapshot, classification, Bound(current, ownSelection), own)
	if len(candidates.Entries) != 1 || candidates.Entries[0].Digest.String() != strings.Repeat("b", 64) {
		t.Fatalf("candidates %+v", candidates.Entries)
	}
	if len(candidates.Staging) != 0 {
		t.Fatalf("the own staging tree was selected: %+v", candidates.Staging)
	}
	// Without the own selection in the bound set, the fresh entry would be selected: the rule, not chance, spares it.
	if without := SelectForCleanup(snapshot, classification, Bound(current), own); len(without.Entries) != 2 {
		t.Fatalf("control case selected %+v", without.Entries)
	}
	// An unknown bound set, while current is damaged, selects no entry and no version.
	damaged := installedObservation(t)
	damaged.Root[0] = layout.File("current", true)
	damaged.Current = nil
	damagedSnapshot := observe(t, damaged)
	if none := SelectForCleanup(damagedSnapshot, layout.Classify(damagedSnapshot), UnknownBound(), Own{}); len(none.Entries) != 0 ||
		len(none.Versions) != 0 {
		t.Fatalf("damaged current selected %+v", none)
	}
}

func TestCleanupDeletesUnboundLeftoversUnderTheProcessUseInvariant(t *testing.T) {
	p := paths(t)
	o := installedObservation(t)
	o.Share = append(o.Share, layout.Directory(".kast-removing-"+id(7), true))
	o.Root = append(o.Root, layout.Directory(".staging-"+id(8), true), layout.Link("current.next", true, "versions/"+id(6)))
	o.Versions = append(o.Versions, layout.Directory(id(6), true))
	o.RuntimeStore = append(o.RuntimeStore, layout.Directory(".kast-runtime-"+id(5), true),
		layout.Directory(freshRuntimeHex, true))
	o.Bin = append(o.Bin, layout.Link("kast.next-"+id(4), true, p.PublicTarget()))
	snapshot := observe(t, o)
	current := layout.Classify(snapshot).(layout.Current).Installed.Selection
	got, rejected := runCleanup(t, o, Bound(current), Own{}, nil)
	if rejected != nil {
		t.Fatal(rejected)
	}
	expectSequence(t, "cleanup", got, []string{
		"unlink R:current.next",
		"unlink PUBLIC_COMMAND:kast.next-" + id(4),
		"rename-aside R:.staging-" + id(8) + " .kast-removing-" + id(1),
		"rename-aside RUNTIME_STORE:.kast-runtime-" + id(5) + " .kast-removing-" + id(2),
		"prove-unused R:versions/" + id(6),
		"rename-aside R:versions/" + id(6) + " .kast-removing-" + id(3),
		"prove-unused RUNTIME_STORE:" + freshRuntimeHex,
		"rename-aside RUNTIME_STORE:" + freshRuntimeHex + " .kast-removing-" + id(4),
		"delete .kast-removing-" + id(7),
		"delete .kast-removing-" + id(1),
		"delete .kast-removing-" + id(2),
		"delete .kast-removing-" + id(3),
		"delete .kast-removing-" + id(4),
	})

	// The process-use inputs block deletion: each use, by executable, argument or working directory, rejects before
	// any effect.
	versionTree := p.Root(record.RootR) + "/versions/" + id(6)
	entryTree := p.Root(record.RootRuntimeStore) + "/" + freshRuntimeHex
	for _, c := range []struct {
		process Process
		use     failure.ProcessUse
	}{
		{Process{PID: 11, Executable: entryTree + "/host/bin/java"}, failure.UseExecutable},
		{Process{PID: 12, Executable: "/usr/bin/java", Arguments: []string{"java", "-cp", versionTree + "/lib/a.jar:" + versionTree + "/lib/b.jar"}}, failure.UseArgument},
		{Process{PID: 13, Executable: "/bin/sh", WorkingDirectory: versionTree}, failure.UseWorkingDirectory},
	} {
		got, rejected := runCleanup(t, o, Bound(current), Own{}, []Process{c.process})
		if rejected == nil || rejected.Code() != failure.ProcessStillAlive {
			t.Fatalf("pid %d: %v", c.process.PID, rejected)
		}
		evidence := rejected.Evidence().(failure.ProcessEvidence)
		if evidence.Holder != c.process.PID || evidence.Use != c.use || len(got.Intents()) != 0 {
			t.Errorf("pid %d: evidence %+v after %d effects", c.process.PID, evidence, len(got.Intents()))
		}
	}
	// A process of the bound version blocks nothing: the current version is not a candidate.
	bound := Process{PID: 14, Executable: p.Root(record.RootR) + "/versions/" + oldHex + "/bin/kast"}
	if _, rejected := runCleanup(t, o, Bound(current), Own{}, []Process{bound}); rejected != nil {
		t.Errorf("a process of the current version blocked cleanup: %v", rejected)
	}
	// A sibling tree whose name merely starts with the same characters is not used.
	if _, ok := UseOf(Process{PID: 15, WorkingDirectory: versionTree + "x"}, versionTree); ok {
		t.Error("a sibling path counted as a use")
	}
}

func TestCleanupRejectsLeftoversKastDidNotCreate(t *testing.T) {
	cases := map[string]struct {
		change func(*layout.Observation)
		want   record.Location
	}{
		"staging link": {func(o *layout.Observation) {
			o.Root = append(o.Root, layout.Link(".staging-"+id(8), true, "/elsewhere"))
		}, record.At(record.RootR, ".staging-"+id(8))},
		"foreign current.next target": {func(o *layout.Observation) {
			o.Root = append(o.Root, layout.Link("current.next", true, "/etc"))
		}, record.At(record.RootR, "current.next")},
		"public next with a foreign target": {func(o *layout.Observation) {
			o.Bin = append(o.Bin, layout.Link("kast.next-"+id(4), true, "/usr/bin/kast"))
		}, record.At(record.RootPublicCommand, "kast.next-"+id(4))},
		"unowned removing tree": {func(o *layout.Observation) {
			o.Share = append(o.Share, layout.Directory(".kast-removing-"+id(7), false))
		}, record.At(record.RootHomeShare, ".kast-removing-"+id(7))},
		"unowned version": {func(o *layout.Observation) {
			o.Versions = append(o.Versions, layout.Directory(id(6), false))
		}, record.At(record.RootR, "versions/"+id(6))},
		"unbound entry that is a link": {func(o *layout.Observation) {
			o.BaseStore = append(o.BaseStore, layout.Link(freshRuntimeHex, true, "/x"))
		}, record.At(record.RootBaseStore, freshRuntimeHex)},
	}
	for name, c := range cases {
		o := installedObservation(t)
		c.change(&o)
		snapshot := observe(t, o)
		current := layout.Classify(snapshot).(layout.Current).Installed.Selection
		got, rejected := runCleanup(t, o, Bound(current), Own{}, nil)
		if rejected == nil || rejected.Code() != failure.LeftoverForeign ||
			rejected.Evidence().(failure.PathEvidence) != (failure.PathEvidence{Root: c.want.Root, Path: c.want.Path}) {
			t.Errorf("%s: %v", name, rejected)
		}
		if len(got.Intents()) != 0 {
			t.Errorf("%s: effects before the rejection: %v", name, strings_(got.Intents()))
		}
	}
}

func TestResidueFinishesInItsFixedOrder(t *testing.T) {
	p := paths(t)
	witness := ".kast-uninstalled-" + id(9)
	alias := "kast-uds-" + strings.Repeat("a", 32)
	o := layout.Observation{
		Share: []layout.Entry{layout.Directory(witness, true), layout.Directory("kast-workspaces", true),
			layout.Directory("kast-runtime-bases", true), layout.Directory(".kast-removing-"+id(7), true),
			layout.File(".kast-workspaces.lifetime.lock", true)},
		RuntimeStore: []layout.Entry{layout.Directory(runtimeHex, true)},
		Bin:          []layout.Entry{publicLink(p)},
		TmpAliases: []layout.Entry{layout.Link(alias, true, p.Root(record.RootR)+"/state/run"),
			layout.Link("kast-uds-"+strings.Repeat("b", 32), true, "/elsewhere/run")},
		Containers: layout.ContainersOccupied,
	}
	got, rejected := runCleanup(t, o, Bound(), Own{}, nil)
	if rejected != nil {
		t.Fatal(rejected)
	}
	expectSequence(t, "residue", got, []string{
		"prove-unused HOME_SHARE:kast-workspaces", "prove-unused HOME_SHARE:kast-runtime-bases",
		"lock-store-lifetime",
		"rename-aside HOME_SHARE:kast-workspaces .kast-removing-" + id(1),
		"rename-aside HOME_SHARE:kast-runtime-bases .kast-removing-" + id(2),
		"unlock-store-lifetime",
		"unlink PUBLIC_COMMAND:kast", "unlink TMP_ALIAS:" + alias,
		"delete .kast-removing-" + id(7), "delete .kast-removing-" + id(1), "delete .kast-removing-" + id(2),
		"prove-unused HOME_SHARE:" + witness, "empty HOME_SHARE:" + witness, "rmdir HOME_SHARE:" + witness,
	})
	steps := map[StepName]bool{}
	for _, step := range got.Steps {
		steps[step.Name] = true
	}
	if !steps["R1"] || !steps["R2"] || !steps["R3"] || !steps["R4"] || got.Steps[len(got.Steps)-1].Name != "R4" {
		t.Errorf("residue steps out of order: %+v", got.Steps)
	}
	// A Host still running from the old store blocks the residue before any effect.
	host := Process{PID: 21, Executable: p.Root(record.RootRuntimeStore) + "/" + runtimeHex + "/host/bin/java"}
	if blocked, rejected := runCleanup(t, o, Bound(), Own{}, []Process{host}); rejected == nil ||
		rejected.Code() != failure.ProcessStillAlive || len(blocked.Intents()) != 0 {
		t.Errorf("a live Host did not block the residue: %v", rejected)
	}
}

func TestAbandonRemovesTheOwnTreesThroughTheRemovingName(t *testing.T) {
	stagingID := must(record.ParseVersionID(id(2)))
	got := Abandon(Own{Staging: &stagingID, Reservations: []Reservation{{EntryRuntime, must(record.ParseVersionID(id(3)))}}},
		sequence{}, 10)
	want := []string{
		"rename-aside R:.staging-" + id(2) + " .kast-removing-" + id(11),
		"rename-aside RUNTIME_STORE:.kast-runtime-" + id(3) + " .kast-removing-" + id(12),
		"delete .kast-removing-" + id(11), "delete .kast-removing-" + id(12),
	}
	if strings.Join(strings_(got), "\n") != strings.Join(want, "\n") {
		t.Errorf("abandon:\n%s", strings.Join(strings_(got), "\n"))
	}
}
