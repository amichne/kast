package frontend

import (
	"bytes"
	"crypto/sha256"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
)

func hostTarget(t *testing.T) Target {
	t.Helper()
	target, ok := parseTarget(platform())
	if !ok {
		t.Fatalf("tests run only on a product target, not %s", platform())
	}
	return target
}

// writeInstalledRuntime lays out, for the installation R/installation, exactly the records a managed installation
// keeps for its runtime, and returns the runtime entry:
//
//   - R/installation/share/kast/product-selection.json: managed_product_installation.py:604-605 writes it into the
//     staged payload's share/kast, which becomes R/installation (:606, :940); its runtimeRoot is the runtime store
//     entry beside R, named by archive digest (managed_product.py:83).
//   - <entry>/runtime.json: written at managed_product_installation.py:183-191.
//   - <entry>/manifest.json: the runtime archive's manifest, copied with the archive tree (:171-173); its SHA-256 is
//     the selection's runtimeManifestSha256 (managed_product.py:85).
//
// Entry records are read-only and the entry root stays private, as at managed_product_installation.py:192-200.
func writeInstalledRuntime(t *testing.T, installation string) string {
	t.Helper()
	entry := filepath.Join(filepath.Dir(filepath.Dir(installation)), "kast-workspaces", "runtimes", fixtureArchive)
	selection := stableSelection(t)
	selection.Target = hostTarget(t)
	selection.RuntimeRoot = entry
	metadata := filepath.Join(installation, "share", "kast")
	for _, directory := range []string{metadata, entry} {
		if err := os.MkdirAll(directory, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	for path, raw := range map[string][]byte{
		filepath.Join(metadata, "product-selection.json"): encodeRecord(t, selection),
		filepath.Join(entry, "runtime.json"):              sharedRecord(t, "runtime.json"),
		filepath.Join(entry, "manifest.json"):             sharedRecord(t, "manifest.json"),
	} {
		if err := os.WriteFile(path, raw, 0o444); err != nil {
			t.Fatal(err)
		}
	}
	return entry
}

// installationBase is a physical directory that stands for HOME/.local/share.
func installationBase(t *testing.T) string {
	t.Helper()
	base, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	return base
}

func replaceRecord(t *testing.T, path string, raw []byte) {
	t.Helper()
	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, raw, 0o444); err != nil {
		t.Fatal(err)
	}
}

func javaCatalog() *Catalog {
	return &Catalog{control: control{JavaHomes: map[string]string{platform(): "host/jbr"}}}
}

func TestTheInstalledSelectionNamesTheJavaHomeInItsBoundRuntimeEntry(t *testing.T) {
	installation := filepath.Join(installationBase(t), "kast", "installation")
	entry := writeInstalledRuntime(t, installation)
	home, failure := javaCatalog().runtimeHome(installation, platform())
	if failure != nil || home != filepath.Join(entry, "host", "jbr") {
		t.Fatalf("runtime home %q %v, want %q", home, failure, filepath.Join(entry, "host", "jbr"))
	}
	// management.json is not an input: its absence changes nothing, and a conflicting one is never read.
	if _, err := os.Stat(filepath.Join(filepath.Dir(installation), "management.json")); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("the fixture must not hold a management receipt: %v", err)
	}
}

func TestEveryInstalledRecordRejectionNamesItsRecordAndCause(t *testing.T) {
	for _, c := range []struct {
		name   string
		damage func(t *testing.T, installation, entry string)
		want   RuntimeFailure
	}{
		{"selection missing", func(t *testing.T, installation, _ string) {
			if err := os.Remove(filepath.Join(installation, "share/kast/product-selection.json")); err != nil {
				t.Fatal(err)
			}
		}, *recordUnavailable(RecordProductSelection, AccessNotFound)},
		{"selection linked", func(t *testing.T, installation, entry string) {
			selection := filepath.Join(installation, "share/kast/product-selection.json")
			moved := filepath.Join(entry, "..", "selection.json")
			if err := os.Rename(selection, moved); err != nil {
				t.Fatal(err)
			}
			if err := os.Symlink(moved, selection); err != nil {
				t.Fatal(err)
			}
		}, *recordUnavailable(RecordProductSelection, AccessLinked)},
		{"selection a directory", func(t *testing.T, installation, _ string) {
			selection := filepath.Join(installation, "share/kast/product-selection.json")
			if err := os.Remove(selection); err != nil {
				t.Fatal(err)
			}
			if err := os.Mkdir(selection, 0o700); err != nil {
				t.Fatal(err)
			}
		}, *recordUnavailable(RecordProductSelection, AccessNotRegular)},
		{"selection a FIFO", func(t *testing.T, installation, _ string) {
			selection := filepath.Join(installation, "share/kast/product-selection.json")
			if err := os.Remove(selection); err != nil {
				t.Fatal(err)
			}
			if err := syscall.Mkfifo(selection, 0o600); err != nil {
				t.Fatal(err)
			}
		}, *recordUnavailable(RecordProductSelection, AccessNotRegular)},
		{"selection oversized", func(t *testing.T, installation, _ string) {
			replaceRecord(t, filepath.Join(installation, "share/kast/product-selection.json"),
				bytes.Repeat([]byte(" "), maximumRecordBytes+1))
		}, *recordInvalid(RecordProductSelection, DecodeTooLarge, "")},
		{"runtime entry missing", func(t *testing.T, _, entry string) {
			if err := os.RemoveAll(entry); err != nil {
				t.Fatal(err)
			}
		}, *recordUnavailable(RecordRuntimeReceipt, AccessNotFound)},
		{"receipt names another manifest", func(t *testing.T, _, entry string) {
			receipt := member(t, sharedRecord(t, "runtime.json"), "manifestSha256", `"`+strings.Repeat("7", 64)+`"`)
			replaceRecord(t, filepath.Join(entry, "runtime.json"), receipt)
		}, *runtimeEntryDamaged(BindingReceiptManifest, digestOf(t, fixtureManifest), digestOf(t, strings.Repeat("7", 64)))},
		{"manifest missing", func(t *testing.T, _, entry string) {
			if err := os.Remove(filepath.Join(entry, "manifest.json")); err != nil {
				t.Fatal(err)
			}
		}, *recordUnavailable(RecordRuntimeManifest, AccessNotFound)},
		{"manifest changed", func(t *testing.T, _, entry string) {
			replaceRecord(t, filepath.Join(entry, "manifest.json"), []byte("{}\n"))
		}, *runtimeEntryDamaged(BindingManifestDigest, digestOf(t, fixtureManifest), sha256.Sum256([]byte("{}\n")))},
	} {
		t.Run(c.name, func(t *testing.T) {
			installation := filepath.Join(installationBase(t), "kast", "installation")
			entry := writeInstalledRuntime(t, installation)
			c.damage(t, installation, entry)
			home, failure := javaCatalog().runtimeHome(installation, platform())
			if failure == nil || *failure != c.want || home != "" {
				t.Fatalf("got %q %v, want %v", home, failure, &c.want)
			}
		})
	}
}

func TestAnUnreadableRecordIsAPermissionFailure(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("the superuser reads every file")
	}
	installation := filepath.Join(installationBase(t), "kast", "installation")
	writeInstalledRuntime(t, installation)
	if err := os.Chmod(filepath.Join(installation, "share/kast/product-selection.json"), 0); err != nil {
		t.Fatal(err)
	}
	if _, failure := javaCatalog().runtimeHome(installation, platform()); failure == nil ||
		*failure != *recordUnavailable(RecordProductSelection, AccessPermission) {
		t.Fatalf("unexpected failure %v", failure)
	}
}

func TestAnOversizedManifestIsRejectedBeforeItIsDigested(t *testing.T) {
	path := filepath.Join(t.TempDir(), "manifest.json")
	if err := os.WriteFile(path, []byte("12345"), 0o444); err != nil {
		t.Fatal(err)
	}
	if _, failure := digestRecord(RecordRuntimeManifest, path, 4); failure == nil ||
		*failure != *recordInvalid(RecordRuntimeManifest, DecodeTooLarge, "") {
		t.Fatalf("unexpected failure %v", failure)
	}
	if digest, failure := digestRecord(RecordRuntimeManifest, path, 5); failure != nil ||
		digest != sha256.Sum256([]byte("12345")) {
		t.Fatalf("a manifest at the bound was not digested: %v", failure)
	}
}

func TestAccessCausesAreMappedFromTheOpenFailure(t *testing.T) {
	for err, want := range map[error]AccessCause{
		syscall.ENOENT:  AccessNotFound,
		syscall.ENOTDIR: AccessNotFound,
		syscall.ELOOP:   AccessLinked,
		syscall.EACCES:  AccessPermission,
		syscall.EIO:     AccessIO,
	} {
		if got := accessCause(&os.PathError{Op: "open", Path: "record", Err: err}); got != want {
			t.Fatalf("%v: %v, want %v", err, got, want)
		}
	}
}

func TestTheSelectionBindsOnlyTheRuntimeEntryBesideItsInstallation(t *testing.T) {
	installation := "/home/kast/.local/share/kast/installation"
	selection := stableSelection(t)
	binding, failure := bindRuntime(installation, TargetMacOSAArch64, selection)
	want := runtimeBinding{
		"/home/kast/.local/share/kast-workspaces/runtimes/" + fixtureArchive,
		digestOf(t, fixtureArchive), digestOf(t, fixtureManifest),
	}
	if failure != nil || binding != want {
		t.Fatalf("binding %+v %v, want %+v", binding, failure, want)
	}
	if _, failure := bindRuntime(installation, TargetLinuxX8664, selection); failure == nil ||
		*failure != *targetMismatch(TargetMacOSAArch64, TargetLinuxX8664) {
		t.Fatalf("a selection for another target was bound: %v", failure)
	}
	for _, moved := range []string{"/home/other/.local/share/kast/installation", "/home/kast/.local/share/kast"} {
		if _, failure := bindRuntime(moved, TargetMacOSAArch64, selection); failure == nil ||
			*failure != *runtimeRootInconsistent() {
			t.Fatalf("%s: a recorded root that is not beside the installation was bound: %v", moved, failure)
		}
	}
	recorded := selection
	recorded.RuntimeRoot = "/home/kast/.local/share/kast-workspaces/runtimes/" + strings.Repeat("5", 64)
	if _, failure := bindRuntime(installation, TargetMacOSAArch64, recorded); failure == nil ||
		*failure != *runtimeRootInconsistent() {
		t.Fatalf("a recorded root that is not the archive's entry was bound: %v", failure)
	}
}

func TestTheRuntimeEntryMustCarryEveryDigestTheSelectionBinds(t *testing.T) {
	archive, manifest, other := digestOf(t, fixtureArchive), digestOf(t, fixtureManifest), digestOf(t, strings.Repeat("9", 64))
	binding := runtimeBinding{"/entry", archive, manifest}
	for _, c := range []struct {
		receipt  runtimeReceipt
		manifest Digest
		want     *RuntimeFailure
	}{
		{runtimeReceipt{archive, manifest, 1}, manifest, nil},
		{runtimeReceipt{other, manifest, 1}, manifest, runtimeEntryDamaged(BindingReceiptArchive, archive, other)},
		{runtimeReceipt{archive, other, 1}, manifest, runtimeEntryDamaged(BindingReceiptManifest, manifest, other)},
		{runtimeReceipt{archive, manifest, 1}, other, runtimeEntryDamaged(BindingManifestDigest, manifest, other)},
	} {
		got := binding.admitEntry(c.receipt, c.manifest)
		if (got == nil) != (c.want == nil) || (got != nil && *got != *c.want) {
			t.Fatalf("%+v %s: %v, want %v", c.receipt, c.manifest, got, c.want)
		}
	}
}

func TestThePlatformAndItsJavaHomeAreAdmittedBeforeAnyRecordIsRead(t *testing.T) {
	missing := filepath.Join(t.TempDir(), "kast", "installation")
	if _, failure := javaCatalog().runtimeHome(missing, "windows-x86_64"); failure == nil ||
		*failure != *platformUnsupported() {
		t.Fatalf("an unsupported platform was admitted: %v", failure)
	}
	for _, relative := range []string{"", "/opt/jbr", "../jbr"} {
		catalog := &Catalog{control: control{JavaHomes: map[string]string{platform(): relative}}}
		if _, failure := catalog.runtimeHome(missing, platform()); failure == nil || *failure != *javaHomeUndeclared() {
			t.Fatalf("java home %q was admitted: %v", relative, failure)
		}
	}
	other := &Catalog{control: control{JavaHomes: map[string]string{"freebsd-riscv64": "jbr"}}}
	if _, failure := other.runtimeHome(missing, platform()); failure == nil || *failure != *javaHomeUndeclared() {
		t.Fatalf("a java home for another platform was used: %v", failure)
	}
}

func TestEveryFailureCodePresentsItsNameAndBoundedEvidence(t *testing.T) {
	archive := digestOf(t, fixtureArchive)
	for _, c := range []struct {
		failure *RuntimeFailure
		want    string
	}{
		{recordUnavailable(RecordProductSelection, AccessNotFound),
			"RECORD_UNAVAILABLE: share/kast/product-selection.json NOT_FOUND"},
		{recordInvalid(RecordRuntimeReceipt, DecodeMissingField, "archiveSha256"),
			"RECORD_INVALID: runtime entry runtime.json MISSING_FIELD archiveSha256"},
		{recordInvalid(RecordProductSelection, DecodeUnknownField, ""),
			"RECORD_INVALID: share/kast/product-selection.json UNKNOWN_FIELD"},
		{recordSchemaUnsupported(RecordProductSelection, 2),
			"RECORD_SCHEMA_UNSUPPORTED: share/kast/product-selection.json schemaVersion 2"},
		{platformUnsupported(), "PLATFORM_UNSUPPORTED"},
		{targetMismatch(TargetLinuxX8664, TargetMacOSAArch64),
			"TARGET_MISMATCH: selection targets linux-x86_64, this binary is macos-aarch64"},
		{runtimeRootInconsistent(), "RUNTIME_ROOT_INCONSISTENT"},
		{runtimeEntryDamaged(BindingManifestDigest, archive, Digest{}),
			"RUNTIME_ENTRY_DAMAGED: manifest.json SHA-256 is " + strings.Repeat("0", 64) + ", the selection binds " +
				fixtureArchive},
		{javaHomeUndeclared(), "JAVA_HOME_UNDECLARED"},
	} {
		if got := c.failure.Error(); got != c.want {
			t.Fatalf("presented %q, want %q", got, c.want)
		}
	}
	seen := map[string]bool{}
	for code := RecordUnavailable; code <= JavaHomeUndeclared; code++ {
		if name := code.String(); name == "" || seen[name] {
			t.Fatalf("code %d has no distinct name", code)
		} else {
			seen[name] = true
		}
	}
	if len(seen) != len(runtimeFailureNames) {
		t.Fatalf("the code table and the constants differ: %v", seen)
	}
}
