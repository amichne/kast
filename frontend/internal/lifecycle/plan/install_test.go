package plan

import (
	"strings"
	"testing"

	"github.com/amichne/kast/frontend/internal/lifecycle/envelope"
	"github.com/amichne/kast/frontend/internal/lifecycle/failure"
	"github.com/amichne/kast/frontend/internal/lifecycle/layout"
	"github.com/amichne/kast/frontend/internal/lifecycle/record"
)

var staged = "R:.staging-" + id(2)

func stagingIntents(version string) []string {
	return []string{
		"create-staging " + staged,
		"download kast-composed-setup-v0.0.42-linux-x86_64.tar.gz",
		"extract-control " + staged,
		"require-layout-marker " + staged,
		"train-archive " + staged + " " + runtimeHex,
		"write-selection " + staged,
		"write-build-record " + staged,
		"write-manifest " + staged + " " + version,
		"seal " + staged,
		"sync-tree " + staged,
	}
}

func joined(parts ...[]string) []string {
	var all []string
	for _, part := range parts {
		all = append(all, part...)
	}
	return all
}

var (
	lockBoth    = []string{"open-lock-file", "lock INTENT EXCLUSIVE", "lock MAIN EXCLUSIVE bounded"}
	downgrade   = []string{"lock MAIN SHARED", "lock INTENT UNLOCKED"}
	relock      = []string{"lock INTENT EXCLUSIVE", "lock MAIN EXCLUSIVE bounded"}
	releaseBoth = []string{"lock MAIN UNLOCKED", "lock INTENT UNLOCKED"}
)

func switchTo(version string) []string {
	return []string{
		"full-sync", "publish-version " + staged + " R:versions/" + version, "sync-dir R:versions",
		"link R:current.next versions/" + version, "swap R:current.next R:current", "sync-dir R:.",
	}
}

func TestUpgradeIntentSequence(t *testing.T) {
	got := Install(upgradeInput(t))
	expectOutcome(t, "upgrade", got, failure.Upgraded)
	expectSequence(t, "upgrade", got, joined(lockBoth,
		[]string{"verify-version " + oldHex, "verify-entry RUNTIME_STORE:" + runtimeHex, "verify-entry BASE_STORE:" + baseHex},
		downgrade,
		[]string{"verify-handoff", "verify-entry BASE_STORE:" + baseHex, "verify-entry RUNTIME_STORE:" + runtimeHex},
		stagingIntents(id(1)), relock,
		[]string{"retire " + oldHex},
		switchTo(id(1)),
		[]string{"prove-unused R:versions/" + oldHex, "rename-aside R:versions/" + oldHex + " .kast-removing-" + id(3),
			"delete .kast-removing-" + id(3), "delete-handoff"},
		releaseBoth))
}

func TestFreshInstallIntentSequence(t *testing.T) {
	got := Install(freshInput(t))
	expectOutcome(t, "install", got, failure.Installed)
	base := "BASE_STORE:.kast-base-" + id(3)
	runtime := "RUNTIME_STORE:.kast-runtime-" + id(4)
	expectSequence(t, "install", got, joined(
		[]string{"mkdir HOME:.local/bin 0755"}, lockBoth,
		[]string{"mkdir R:. 0700", "sync-dir HOME_SHARE:.", "mkdir R:versions 0700", "sync-dir R:."},
		downgrade,
		[]string{"verify-handoff",
			"mkdir HOME_SHARE:kast-runtime-bases 0700",
			"download kast-runtime-base-linux-x86_64-" + baseHex + ".zip",
			"reserve " + base, "extract base " + base, "sync-tree " + base, "full-sync",
			"publish " + base + " BASE_STORE:" + baseHex, "sync-dir BASE_STORE:.",
			"mkdir HOME_SHARE:kast-workspaces 0700", "mkdir RUNTIME_STORE:. 0700",
			"download kast-runtime-v0.0.42-linux-x86_64.zip",
			"reserve " + runtime, "extract runtime " + runtime, "sync-tree " + runtime, "full-sync",
			"publish " + runtime + " RUNTIME_STORE:" + runtimeHex, "sync-dir RUNTIME_STORE:."},
		stagingIntents(id(1)), relock, switchTo(id(1)),
		[]string{"link PUBLIC_COMMAND:kast.next-" + id(5), "place-public kast.next-" + id(5) + " no-replace",
			"sync-dir PUBLIC_COMMAND:.", "delete-handoff"},
		releaseBoth))
}

func TestUnchangedRecordIsAlreadyCurrent(t *testing.T) {
	in := upgradeInput(t)
	in.Handoff, in.RecordBytes = handoff(t, envelope.OperationUpgrade, buildRecord(t)), buildRecord(t)
	got := Install(in)
	expectOutcome(t, "already current", got, failure.AlreadyCurrent)
	expectSequence(t, "already current", got, joined(lockBoth,
		[]string{"verify-version " + oldHex, "verify-entry RUNTIME_STORE:" + runtimeHex,
			"verify-entry BASE_STORE:" + baseHex, "verify-handoff", "delete-handoff"},
		releaseBoth))

	in.Locked = observe(t, func() layout.Observation { o := installedObservation(t); o.Bin = nil; return o }())
	expectSequence(t, "already current ensures the link", Install(in), joined(lockBoth,
		[]string{"verify-version " + oldHex, "verify-entry RUNTIME_STORE:" + runtimeHex,
			"verify-entry BASE_STORE:" + baseHex, "verify-handoff", "link PUBLIC_COMMAND:kast.next-" + id(1),
			"place-public kast.next-" + id(1) + " no-replace", "sync-dir PUBLIC_COMMAND:.", "delete-handoff"},
		releaseBoth))
}

func TestRepairReplacesDamagedEntriesAndRetiresByForce(t *testing.T) {
	in := upgradeInput(t)
	in.Operation = envelope.OperationRepair
	in.Handoff, in.RecordBytes = handoff(t, envelope.OperationRepair, buildRecord(t)), buildRecord(t)
	damage := failure.NewRuntimeEntryDamaged(failure.StageVerify, record.At(record.RootRuntimeStore, runtimeHex))
	retirement := failure.NewRetirementExecutableDamaged(failure.StageVerify,
		record.At(record.RootR, "versions/"+oldHex+"/share/kast/libexec/kast-service"))
	in.Current = CurrentVerification{Runtime: &damage, Retirement: &retirement}
	in.Runtime = EntryObservation{Presence: EntryPresent, Damage: &damage}
	host := Process{PID: 4100, StartTime: 5, Executable: paths(t).Root(record.RootRuntimeStore) + "/" + runtimeHex +
		"/host/bin/java"}
	in.Processes = []Process{host}
	in.Forced = ForcedObservation{
		Supervisor: SupervisorObservation{Available: true, Kind: failure.SupervisorSystemd},
		Entries: []SupervisorEntry{
			{Label: semantic, Program: paths(t).Root(record.RootR) + "/versions/" + oldHex + "/bin/kast"},
			{Label: "io.github.amichne.kast.semantic." + strings.Repeat("1", 32), Program: "/opt/other/bin/kast"},
		},
	}
	got := Install(in)
	expectOutcome(t, "repair", got, failure.Repaired)
	reserved := "RUNTIME_STORE:.kast-runtime-" + id(3)
	expectSequence(t, "repair", got, joined(lockBoth,
		[]string{"verify-version " + oldHex, "verify-entry RUNTIME_STORE:" + runtimeHex, "verify-entry BASE_STORE:" + baseHex},
		downgrade,
		[]string{"verify-handoff", "verify-entry BASE_STORE:" + baseHex, "verify-entry RUNTIME_STORE:" + runtimeHex,
			"mkdir RUNTIME_STORE:. 0700", "download kast-runtime-v0.0.42-linux-x86_64.zip", "reserve " + reserved,
			"extract runtime " + reserved, "sync-tree " + reserved},
		stagingIntents(id(1)), relock,
		[]string{"stop SYSTEMD " + semantic, "await-absent SYSTEMD " + semantic, "terminate 4100@5",
			"exchange " + reserved + " RUNTIME_STORE:" + runtimeHex, "sync-dir RUNTIME_STORE:."},
		switchTo(id(1)),
		[]string{"prove-unused R:versions/" + oldHex, "rename-aside R:versions/" + oldHex + " .kast-removing-" + id(4),
			"rename-aside " + reserved + " .kast-removing-" + id(5),
			"delete .kast-removing-" + id(4), "delete .kast-removing-" + id(5), "delete-handoff"},
		releaseBoth))
}

func TestInstallRejectionsNameTheirCheckAndReleaseTheLock(t *testing.T) {
	legacy := installedObservation(t)
	legacy.Root = append(legacy.Root, layout.File("management.json", true))
	foreignLink := installedObservation(t)
	foreignLink.Bin = []layout.Entry{layout.Link("kast", true, "/usr/local/bin/kast")}
	cases := map[string]struct {
		change      func(*InstallInput)
		want        failure.Code
		lastIntents []string
	}{
		"legacy before any effect": {func(in *InstallInput) { in.Before = observe(t, legacy) }, failure.LegacyLayout, nil},
		"root user":                {func(in *InstallInput) { in.Home.UserID = 0 }, failure.RootUserRejected, nil},
		"home link":                {func(in *InstallInput) { in.Home.Share = PathRejected }, failure.HomePathRejected, nil},
		"network share": {func(in *InstallInput) {
			in.Home.Filesystems = []FilesystemFact{{Root: record.RootHomeShare, Network: true}}
		}, failure.FilesystemUnsupported, nil},
		"cross device": {func(in *InstallInput) {
			in.Home.Filesystems = []FilesystemFact{{Root: record.RootHomeShare, Device: 1}, {Root: record.RootR, Device: 2}}
		}, failure.CrossDevice, nil},
		"legacy raced in under the lock": {func(in *InstallInput) { in.Locked = observe(t, legacy) },
			failure.LegacyLayout, releaseBoth},
		"foreign public command": {func(in *InstallInput) { in.Locked = observe(t, foreignLink) },
			failure.PublicCommandForeign, releaseBoth},
		"damaged current without repair": {func(in *InstallInput) {
			damage := failure.NewPayloadFileDamaged(failure.StageVerify, record.At(record.RootR, "versions/"+oldHex+"/bin/kast"))
			in.Current.Payload = &damage
		}, failure.PayloadFileDamaged, releaseBoth},
		"record digest": {func(in *InstallInput) { in.RecordBytes = buildRecord(t) }, failure.RecordDigestMismatch,
			[]string{"verify-handoff", "lock MAIN UNLOCKED"}},
		"own executable": {func(in *InstallInput) { in.OwnExecutable = digest(strings.Repeat("d", 64)) },
			failure.ControlDigestMismatch, []string{"verify-handoff", "lock MAIN UNLOCKED"}},
		"layout marker": {func(in *InstallInput) { in.LayoutMarker = false }, failure.ReleaseLayoutUnsupported,
			[]string{"require-layout-marker " + staged, "rename-aside " + staged + " .kast-removing-" + id(3),
				"delete .kast-removing-" + id(3), "lock MAIN UNLOCKED"}},
		"retirement rejected": {func(in *InstallInput) {
			in.Retirement.Document = []byte(`{"schemaVersion":1,"outcome":"REJECTED","reason":"LOGIN_AGENT_FOREIGN"}`)
			in.Retirement.Exit = must(failure.ParseExitCode(3))
		}, failure.RetirementRejected, []string{"retire " + oldHex, "rename-aside " + staged + " .kast-removing-" + id(3),
			"delete .kast-removing-" + id(3), "lock MAIN UNLOCKED", "lock INTENT UNLOCKED"}},
		"upgrade with nothing installed": {func(in *InstallInput) {
			in.Before, in.Locked = observe(t, layout.Observation{}), observe(t, layout.Observation{})
		}, failure.LayoutDamaged, releaseBoth},
	}
	for name, c := range cases {
		in := upgradeInput(t)
		c.change(&in)
		got := Install(in)
		expectFailure(t, name, got, c.want)
		intents := strings_(got.Intents())
		if c.lastIntents == nil {
			if len(intents) != 0 {
				t.Errorf("%s: a P0 rejection had effects: %v", name, intents)
			}
			continue
		}
		if tail := intents[max(0, len(intents)-len(c.lastIntents)):]; strings.Join(tail, "\n") !=
			strings.Join(c.lastIntents, "\n") {
			t.Errorf("%s: plan ends\n  %s\nwant\n  %s", name, strings.Join(tail, "\n  "),
				strings.Join(c.lastIntents, "\n  "))
		}
	}
}

func TestFirstInstallNeedsAnEmptyContainerStore(t *testing.T) {
	in := freshInput(t)
	occupied := layout.Observation{Share: []layout.Entry{layout.Directory("kast-workspaces", true)},
		Containers: layout.ContainersOccupied}
	in.Before, in.Locked = observe(t, occupied), observe(t, occupied)
	expectFailure(t, "occupied", Install(in), failure.WorkspaceStoreOccupied)
}

func TestNewerRecordsRejectEvenWithRepair(t *testing.T) {
	in := upgradeInput(t)
	in.Operation = envelope.OperationRepair
	newer := installedObservation(t)
	records := *newer.Current
	records.Selection = layout.Contents([]byte(`{"schemaVersion":3}`))
	newer.Current = &records
	in.Locked = observe(t, newer)
	expectFailure(t, "newer selection", Install(in), failure.RecordSchemaUnsupported)
}
