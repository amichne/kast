package failure_test

import (
	"bytes"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/amichne/kast/frontend/internal/lifecycle/codec"
	"github.com/amichne/kast/frontend/internal/lifecycle/failure"
	"github.com/amichne/kast/frontend/internal/lifecycle/record"
)

func lines(t *testing.T, name string) [][]byte {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("testdata", name))
	if err != nil {
		t.Fatal(err)
	}
	var out [][]byte
	for _, line := range bytes.SplitAfter(raw, []byte("\n")) {
		if len(line) > 0 {
			out = append(out, line)
		}
	}
	return out
}

func must[T any](value T, ok bool) T {
	if !ok {
		panic("literal value rejected")
	}
	return value
}

// constructed builds every code through its one constructor, in declaration order, with the evidence the fixture
// file spells independently.
func constructed() []failure.Failure {
	a := must(record.ParseDigest(strings.Repeat("a", 64)))
	b := must(record.ParseDigest(strings.Repeat("b", 64)))
	recordAsset := record.BuildRecordAsset
	envelopeAsset := record.MustAssetName("lifecycle-envelope.json")
	current := record.At(record.RootR, "current")
	handoff := record.At(record.RootHandoff, "handoff.json")
	notFound := must(failure.TransferStatus(404))
	timeout := must(failure.TransferFailed(failure.TransferTimeout))
	semantic := must(record.ParseServiceLabel("io.github.amichne.kast.semantic.0123456789abcdef0123456789abcdef"))
	unmapped := must(failure.Unmapped(75))
	s, h, v, d, st := failure.StageSelection, failure.StageHandoff, failure.StageVerify, failure.StageDownload,
		failure.StageStage
	p, pre, c, r := failure.StagePrecheck, failure.StagePrelude, failure.StageCleanup, failure.StageRetire
	return []failure.Failure{
		failure.NewSelectorInvalid(s),
		failure.NewSelectorsConflict(s),
		failure.NewPlatformUnsupported(s),
		failure.NewCatalogUnavailable(s, envelopeAsset, notFound),
		failure.NewCatalogInvalid(s),
		failure.NewCommitAmbiguous(s),
		failure.NewBuildUnavailable(s),
		failure.NewReleaseLayoutUnsupported(s),
		failure.NewHandoffUnsupported(h, handoff),
		failure.NewHandoffInvalid(h, handoff),
		failure.NewRecordDigestMismatch(v, recordAsset, a, b),
		failure.NewRecordInvalid(v, current),
		failure.NewRecordSchemaUnsupported(v, current),
		failure.NewAssetUnavailable(d, envelopeAsset, timeout),
		failure.NewAssetSizeMismatch(d, recordAsset, a, b),
		failure.NewAssetDigestMismatch(d, recordAsset, a, b),
		failure.NewQualificationInvalid(v),
		failure.NewArchiveEntryRejected(st, current),
		failure.NewBundleInventoryInvalid(st, current),
		failure.NewControlDigestMismatch(v, recordAsset, a, b),
		failure.NewLocalArtifactRequired(s),
		failure.NewRuntimeEntryDamaged(v, current),
		failure.NewBaseEntryDamaged(v, current),
		failure.NewPayloadManifestInvalid(v, current),
		failure.NewPayloadFileDamaged(v, current),
		failure.NewRetirementExecutableDamaged(v, current),
		failure.NewHomeInvalid(p),
		failure.NewHomePathRejected(p, current),
		failure.NewRootUserRejected(p),
		failure.NewFilesystemUnsupported(p, current),
		failure.NewCrossDevice(p, current),
		failure.NewRootForeign(p, current),
		failure.NewLegacyLayout(p, current),
		failure.NewLayoutDamaged(p, current),
		failure.NewPublicCommandForeign(pre, current),
		failure.NewWorkspaceStoreOccupied(pre, current),
		failure.NewLeftoverForeign(c, current),
		failure.NewLockFileRejected(pre, current),
		failure.NewLockHeld(pre, failure.RangeMain, failure.ModeShared, 4242),
		failure.NewProcessStillAlive(c, 77, failure.UseArgument),
		failure.NewRetirementRejected(r, must(failure.ParseExitCode(3)), record.ReasonLoginAgentForeign),
		failure.NewRetirementTimedOut(r, must(failure.ParseExitCode(-1)), record.ReasonDeadlineExceeded),
		failure.NewRemovalBlocked(failure.StageRemove, must(failure.ParseExitCode(2)), record.ReasonActiveHost),
		failure.NewSupervisorStillActive(r, failure.SupervisorSystemd, semantic, failure.StateDeactivating),
		failure.NewFilesystemRejected(failure.StagePublish, record.At(record.RootR, "versions"),
			failure.OpRenameNoReplace, unmapped),
	}
}

func TestEveryCodeHasOneConstructorAndItsOwnEvidence(t *testing.T) {
	built := constructed()
	codes := failure.Codes()
	if len(built) != len(codes) {
		t.Fatalf("%d constructors for %d codes", len(built), len(codes))
	}
	evidenceTypes := map[failure.EvidenceKind]reflect.Type{
		failure.KindNone:        reflect.TypeOf(failure.NoEvidence{}),
		failure.KindAssetDigest: reflect.TypeOf(failure.AssetDigestEvidence{}),
		failure.KindPath:        reflect.TypeOf(failure.PathEvidence{}),
		failure.KindFilesystem:  reflect.TypeOf(failure.FilesystemEvidence{}),
		failure.KindTransfer:    reflect.TypeOf(failure.TransferEvidence{}),
		failure.KindHolder:      reflect.TypeOf(failure.HolderEvidence{}),
		failure.KindProcess:     reflect.TypeOf(failure.ProcessEvidence{}),
		failure.KindChild:       reflect.TypeOf(failure.ChildEvidence{}),
		failure.KindSupervisor:  reflect.TypeOf(failure.SupervisorEvidence{}),
	}
	for i, value := range built {
		if value.Code() != codes[i] {
			t.Errorf("constructor %d builds %v, want %v", i, value.Code(), codes[i])
		}
		if got, want := reflect.TypeOf(value.Evidence()), evidenceTypes[failure.EvidenceOf(value.Code())]; got != want {
			t.Errorf("%v carries %v, want %v", value.Code(), got, want)
		}
	}
	failureType := reflect.TypeOf(failure.Failure{})
	for i := 0; i < failureType.NumField(); i++ {
		if failureType.Field(i).IsExported() {
			t.Errorf("Failure exports field %s, so a code could be paired with foreign evidence", failureType.Field(i).Name)
		}
	}
}

func TestFailureDocumentsMatchIndependentFixtures(t *testing.T) {
	expected := lines(t, "failures.jsonl")
	built := constructed()
	if len(expected) != len(built) {
		t.Fatalf("%d fixture lines for %d codes", len(expected), len(built))
	}
	for i, value := range built {
		if !bytes.Equal(value.Encode(), expected[i]) {
			t.Errorf("%v encodes\n%s want\n%s", value.Code(), value.Encode(), expected[i])
		}
		decoded, err := failure.DecodeFailure(expected[i])
		if err != nil {
			t.Errorf("%v: %v", value.Code(), err)
			continue
		}
		if !reflect.DeepEqual(decoded, value) {
			t.Errorf("%v decodes to %+v", value.Code(), decoded)
		}
	}
}

func TestDecoderRejectsEvidenceOfAnotherCode(t *testing.T) {
	expected := lines(t, "failures.jsonl")
	byCode := map[string][]byte{}
	for _, line := range expected {
		decoded, err := failure.DecodeFailure(line)
		if err != nil {
			t.Fatal(err)
		}
		byCode[decoded.Code().String()] = line
	}
	evidenceOf := func(line []byte) string {
		return string(line[bytes.Index(line, []byte(`"evidence":`)):])
	}
	headOf := func(line []byte) string {
		return string(line[:bytes.Index(line, []byte(`"evidence":`))])
	}
	mismatches := [][2]string{
		{"LOCK_HELD", "ROOT_FOREIGN"},
		{"ROOT_FOREIGN", "LOCK_HELD"},
		{"SELECTOR_INVALID", "ROOT_FOREIGN"},
		{"FILESYSTEM_REJECTED", "ROOT_FOREIGN"},
		{"PROCESS_STILL_ALIVE", "LOCK_HELD"},
		{"RETIREMENT_REJECTED", "SUPERVISOR_STILL_ACTIVE"},
		{"CATALOG_UNAVAILABLE", "RECORD_DIGEST_MISMATCH"},
	}
	for _, pair := range mismatches {
		document := headOf(byCode[pair[0]]) + evidenceOf(byCode[pair[1]])
		if _, err := failure.DecodeFailure([]byte(document)); err == nil {
			t.Errorf("%s decoded with %s evidence", pair[0], pair[1])
		}
	}
	rejections := map[string]string{
		"reason of another child code": strings.Replace(string(byCode["REMOVAL_BLOCKED"]), "ACTIVE_HOST",
			"LOGIN_AGENT_FOREIGN", 1),
		"timeout with a document reason": strings.Replace(string(byCode["RETIREMENT_TIMED_OUT"]), "DEADLINE_EXCEEDED",
			"ACTIVE_HOST", 1),
		"errno with a mapped cause": strings.Replace(string(byCode["FILESYSTEM_REJECTED"]), "UNMAPPED_ERRNO",
			"PERMISSION", 1),
		"status without HTTP_STATUS": strings.Replace(string(byCode["CATALOG_UNAVAILABLE"]), "HTTP_STATUS",
			"TIMEOUT", 1),
		"unknown code":    strings.Replace(string(byCode["SELECTOR_INVALID"]), "SELECTOR_INVALID", "SELECTOR_BAD", 1),
		"unknown stage":   strings.Replace(string(byCode["SELECTOR_INVALID"]), "SELECTION", "SELECTING", 1),
		"evidence member": strings.Replace(string(byCode["SELECTOR_INVALID"]), `{}`, `{"message":"x"}`, 1),
		"absolute path": strings.Replace(string(byCode["ROOT_FOREIGN"]), `"path":"current"`,
			`"path":"/home/user/x"`, 1),
		"newer document": strings.Replace(string(byCode["SELECTOR_INVALID"]), `"schemaVersion":1`,
			`"schemaVersion":2`, 1),
	}
	for name, document := range rejections {
		if _, err := failure.DecodeFailure([]byte(document)); err == nil {
			t.Errorf("%s decoded", name)
		}
	}
	if _, err := failure.DecodeFailure([]byte(strings.Replace(string(byCode["SELECTOR_INVALID"]), `"evidence":{}`,
		`"evidence":null`, 1))); err == nil {
		t.Error("null evidence decoded")
	} else if cause, _ := codec.CauseOf(err); cause != codec.NullMember {
		t.Errorf("null evidence: %v", err)
	}
}

func TestConstructorsRefuseInvalidEvidence(t *testing.T) {
	if _, ok := failure.Mapped(failure.CauseUnmappedErrno); ok {
		t.Error("UNMAPPED_ERRNO built without its number")
	}
	if _, ok := failure.Unmapped(0); ok {
		t.Error("errno 0 admitted")
	}
	if _, ok := failure.Unmapped(failure.MaximumErrno + 1); ok {
		t.Error("unbounded errno admitted")
	}
	if _, ok := failure.TransferFailed(failure.TransferHTTPStatus); ok {
		t.Error("HTTP_STATUS built without its status")
	}
	if _, ok := failure.TransferStatus(600); ok {
		t.Error("status 600 admitted")
	}
	if _, ok := failure.ParseExitCode(256); ok {
		t.Error("exit code 256 admitted")
	}
	panics := map[string]func(){
		"removal with a retirement reason": func() {
			failure.NewRemovalBlocked(failure.StageRemove, must(failure.ParseExitCode(1)), record.ReasonLoginAgentForeign)
		},
		"retirement with a removal reason": func() {
			failure.NewRetirementRejected(failure.StageRetire, must(failure.ParseExitCode(1)), record.ReasonActiveHost)
		},
		"timeout without the deadline": func() {
			failure.NewRetirementTimedOut(failure.StageRetire, must(failure.ParseExitCode(1)), record.ReasonActiveHost)
		},
		"zero location":   func() { failure.NewRootForeign(failure.StagePrecheck, record.Location{}) },
		"zero stage":      func() { failure.NewSelectorInvalid(0) },
		"zero process id": func() { failure.NewProcessStillAlive(failure.StageCleanup, 0, failure.UseArgument) },
		"zero lock range": func() { failure.NewLockHeld(failure.StagePrelude, 0, failure.ModeShared, 1) },
	}
	for name, construct := range panics {
		func() {
			defer func() {
				if recover() == nil {
					t.Errorf("%s was built", name)
				}
			}()
			construct()
		}()
	}
}

func TestOutcomesAreASeparateUnionWithCanonicalDocuments(t *testing.T) {
	expected := lines(t, "outcomes.jsonl")
	broker := must(record.ParseServiceLabel("io.github.amichne.kast.broker.00000000000000000000000000000001"))
	removing := must(record.ParseVersionID("0123456789abcdef"))
	outcomes := []failure.Outcome{
		failure.NewOutcome(failure.Installed),
		failure.NewOutcome(failure.Upgraded, failure.SupervisorUnavailable{}),
		failure.NewOutcome(failure.Repaired, failure.ResidueRetained{Removing: removing}),
		failure.NewOutcome(failure.AlreadyCurrent),
		failure.NewOutcome(failure.Uninstalled, failure.LoginAgentRetained{Label: broker},
			failure.SupervisorUnavailable{}),
		failure.NewOutcome(failure.UninstalledWithoutUserState),
	}
	if len(outcomes) != len(expected) {
		t.Fatalf("%d outcomes for %d fixture lines", len(outcomes), len(expected))
	}
	for i, outcome := range outcomes {
		if !bytes.Equal(outcome.Encode(), expected[i]) {
			t.Errorf("%v encodes\n%s want\n%s", outcome.Kind(), outcome.Encode(), expected[i])
		}
	}
	for _, code := range failure.Codes() {
		if strings.Contains(code.String(), "ALREADY_CURRENT") || strings.HasPrefix(code.String(), "INSTALLED") {
			t.Errorf("outcome %v is spelled as a failure code", code)
		}
	}
}
