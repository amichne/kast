package frontend

import (
	"crypto/sha256"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"syscall"
)

// The frontend runs every JVM entry on java from the Host runtime that the installation's own selection binds. It
// derives that runtime from records the installation keeps: the selection names the runtime entry by its archive
// digest and binds the entry's manifest digest, and the entry's receipt must name both.

// RuntimeFailureCode is the closed set of reasons the frontend cannot name the Host runtime's java.
type RuntimeFailureCode uint8

const (
	RecordUnavailable RuntimeFailureCode = iota + 1
	RecordInvalid
	RecordSchemaUnsupported
	PlatformUnsupported
	TargetMismatch
	RuntimeRootInconsistent
	RuntimeEntryDamaged
	JavaHomeUndeclared
)

var runtimeFailureNames = map[RuntimeFailureCode]string{
	RecordUnavailable:       "RECORD_UNAVAILABLE",
	RecordInvalid:           "RECORD_INVALID",
	RecordSchemaUnsupported: "RECORD_SCHEMA_UNSUPPORTED",
	PlatformUnsupported:     "PLATFORM_UNSUPPORTED",
	TargetMismatch:          "TARGET_MISMATCH",
	RuntimeRootInconsistent: "RUNTIME_ROOT_INCONSISTENT",
	RuntimeEntryDamaged:     "RUNTIME_ENTRY_DAMAGED",
	JavaHomeUndeclared:      "JAVA_HOME_UNDECLARED",
}

func (c RuntimeFailureCode) String() string { return runtimeFailureNames[c] }

// RecordKind names a record by its place, never by an absolute path.
type RecordKind uint8

const (
	RecordProductSelection RecordKind = iota + 1
	RecordRuntimeReceipt
	RecordRuntimeManifest
)

var recordLocations = map[RecordKind]string{
	RecordProductSelection: "share/kast/product-selection.json",
	RecordRuntimeReceipt:   "runtime entry runtime.json",
	RecordRuntimeManifest:  "runtime entry manifest.json",
}

func (k RecordKind) String() string { return recordLocations[k] }

// AccessCause is the closed set of reasons a record cannot be read.
type AccessCause uint8

const (
	AccessNotFound AccessCause = iota + 1
	AccessLinked
	AccessNotRegular
	AccessPermission
	AccessIO
)

var accessNames = map[AccessCause]string{
	AccessNotFound: "NOT_FOUND", AccessLinked: "LINKED", AccessNotRegular: "NOT_REGULAR",
	AccessPermission: "PERMISSION", AccessIO: "IO",
}

func (c AccessCause) String() string { return accessNames[c] }

// DecodeCause is the closed set of reasons a record's bytes are not an admitted record.
type DecodeCause uint8

const (
	DecodeTooLarge DecodeCause = iota + 1
	DecodeSyntax
	DecodeNotObject
	DecodeDuplicateKey
	DecodeTrailingData
	DecodeUnknownField
	DecodeMissingField
	DecodeFieldValue
)

var decodeNames = map[DecodeCause]string{
	DecodeTooLarge: "TOO_LARGE", DecodeSyntax: "SYNTAX", DecodeNotObject: "NOT_OBJECT",
	DecodeDuplicateKey: "DUPLICATE_KEY", DecodeTrailingData: "TRAILING_DATA", DecodeUnknownField: "UNKNOWN_FIELD",
	DecodeMissingField: "MISSING_FIELD", DecodeFieldValue: "FIELD_VALUE",
}

func (c DecodeCause) String() string { return decodeNames[c] }

// RecordField is a declared field name of the record it is reported for, or empty when no declared field applies.
// An unknown field is never echoed.
type RecordField string

// EntryBinding is the closed set of runtime entry facts that must equal the selection's binding.
type EntryBinding uint8

const (
	BindingReceiptArchive EntryBinding = iota + 1
	BindingReceiptManifest
	BindingManifestDigest
)

var bindingNames = map[EntryBinding]string{
	BindingReceiptArchive:  "runtime.json archiveSha256",
	BindingReceiptManifest: "runtime.json manifestSha256",
	BindingManifestDigest:  "manifest.json SHA-256",
}

func (b EntryBinding) String() string { return bindingNames[b] }

// RuntimeFailure is one rejection. Each code has one constructor that takes exactly that code's evidence, so a code
// cannot be paired with another code's evidence. Evidence is bounded and names no absolute path.
type RuntimeFailure struct {
	code     RuntimeFailureCode
	evidence runtimeEvidence
}

type runtimeEvidence interface{ describe() string }

type recordAccessEvidence struct {
	record RecordKind
	cause  AccessCause
}

type recordDecodeEvidence struct {
	record RecordKind
	cause  DecodeCause
	field  RecordField
}

type recordSchemaEvidence struct {
	record   RecordKind
	observed int
}

type targetEvidence struct{ recorded, platform Target }

type entryDigestEvidence struct {
	binding            EntryBinding
	expected, observed Digest
}

type noRuntimeEvidence struct{}

func (e recordAccessEvidence) describe() string { return e.record.String() + " " + e.cause.String() }

func (e recordDecodeEvidence) describe() string {
	text := e.record.String() + " " + e.cause.String()
	if e.field != "" {
		text += " " + string(e.field)
	}
	return text
}

func (e recordSchemaEvidence) describe() string {
	return fmt.Sprintf("%s schemaVersion %d", e.record, e.observed)
}

func (e targetEvidence) describe() string {
	return "selection targets " + e.recorded.String() + ", this binary is " + e.platform.String()
}

func (e entryDigestEvidence) describe() string {
	return fmt.Sprintf("%s is %s, the selection binds %s", e.binding, e.observed, e.expected)
}

func (noRuntimeEvidence) describe() string { return "" }

func recordUnavailable(record RecordKind, cause AccessCause) *RuntimeFailure {
	return &RuntimeFailure{RecordUnavailable, recordAccessEvidence{record, cause}}
}

func recordInvalid(record RecordKind, cause DecodeCause, field RecordField) *RuntimeFailure {
	return &RuntimeFailure{RecordInvalid, recordDecodeEvidence{record, cause, field}}
}

func recordSchemaUnsupported(record RecordKind, observed int) *RuntimeFailure {
	return &RuntimeFailure{RecordSchemaUnsupported, recordSchemaEvidence{record, observed}}
}

func platformUnsupported() *RuntimeFailure {
	return &RuntimeFailure{PlatformUnsupported, noRuntimeEvidence{}}
}

func targetMismatch(recorded, platform Target) *RuntimeFailure {
	return &RuntimeFailure{TargetMismatch, targetEvidence{recorded, platform}}
}

func runtimeRootInconsistent() *RuntimeFailure {
	return &RuntimeFailure{RuntimeRootInconsistent, noRuntimeEvidence{}}
}

func runtimeEntryDamaged(binding EntryBinding, expected, observed Digest) *RuntimeFailure {
	return &RuntimeFailure{RuntimeEntryDamaged, entryDigestEvidence{binding, expected, observed}}
}

func javaHomeUndeclared() *RuntimeFailure {
	return &RuntimeFailure{JavaHomeUndeclared, noRuntimeEvidence{}}
}

// Code reports the failure's code.
func (f *RuntimeFailure) Code() RuntimeFailureCode { return f.code }

func (f *RuntimeFailure) Error() string {
	if detail := f.evidence.describe(); detail != "" {
		return f.code.String() + ": " + detail
	}
	return f.code.String()
}

// runtimeBinding is the runtime entry a selection binds and the digests that entry must carry.
type runtimeBinding struct {
	root     string
	archive  Digest
	manifest Digest
}

// bindRuntime derives the runtime entry from the installation's selection. The installation is R/installation, and
// the installer places runtime entries in the runtime store beside R, named by archive digest (managed_product.py:83).
// The selection's recorded runtime root must be exactly that entry; a disagreement is never resolved by choosing one.
func bindRuntime(installation string, platform Target, selection productSelection) (runtimeBinding, *RuntimeFailure) {
	if selection.Target != platform {
		return runtimeBinding{}, targetMismatch(selection.Target, platform)
	}
	root := filepath.Join(filepath.Dir(filepath.Dir(installation)), "kast-workspaces", "runtimes",
		selection.RuntimeArchiveSHA256.String())
	if selection.RuntimeRoot != root {
		return runtimeBinding{}, runtimeRootInconsistent()
	}
	return runtimeBinding{root, selection.RuntimeArchiveSHA256, selection.RuntimeManifestSHA256}, nil
}

// admitEntry requires the entry's receipt and manifest to be the ones the selection binds, as the installer checks
// before it reuses an entry (managed_product_installation.py:110-121).
func (b runtimeBinding) admitEntry(receipt runtimeReceipt, manifest Digest) *RuntimeFailure {
	switch {
	case receipt.ArchiveSHA256 != b.archive:
		return runtimeEntryDamaged(BindingReceiptArchive, b.archive, receipt.ArchiveSHA256)
	case receipt.ManifestSHA256 != b.manifest:
		return runtimeEntryDamaged(BindingReceiptManifest, b.manifest, receipt.ManifestSHA256)
	case manifest != b.manifest:
		return runtimeEntryDamaged(BindingManifestDigest, b.manifest, manifest)
	}
	return nil
}

// javaHomeFor selects the catalog's Java home for this platform, relative to the runtime entry.
func (c *Catalog) javaHomeFor(platform Target) (string, *RuntimeFailure) {
	relative, declared := c.control.JavaHomes[platform.String()]
	if !declared || !filepath.IsLocal(relative) {
		return "", javaHomeUndeclared()
	}
	return relative, nil
}

// runtimeHome is the effect boundary that names the Java home every JVM entry runs on, for the binary's public
// platform name. It reads the installation's selection, then the entry's receipt, and digests the entry's manifest.
// The JVM entry re-admits its own runtime before any effect.
func (c *Catalog) runtimeHome(installation, platformName string) (string, *RuntimeFailure) {
	target, supported := parseTarget(platformName)
	if !supported {
		return "", platformUnsupported()
	}
	relative, failure := c.javaHomeFor(target)
	if failure != nil {
		return "", failure
	}
	raw, failure := readRecord(RecordProductSelection, filepath.Join(installation, "share", "kast",
		"product-selection.json"))
	if failure != nil {
		return "", failure
	}
	selection, failure := decodeProductSelection(raw)
	if failure != nil {
		return "", failure
	}
	binding, failure := bindRuntime(installation, target, selection)
	if failure != nil {
		return "", failure
	}
	raw, failure = readRecord(RecordRuntimeReceipt, filepath.Join(binding.root, "runtime.json"))
	if failure != nil {
		return "", failure
	}
	receipt, failure := decodeRuntimeReceipt(raw)
	if failure != nil {
		return "", failure
	}
	manifest, failure := digestRecord(RecordRuntimeManifest, filepath.Join(binding.root, "manifest.json"),
		maximumManifestBytes)
	if failure != nil {
		return "", failure
	}
	if failure := binding.admitEntry(receipt, manifest); failure != nil {
		return "", failure
	}
	return filepath.Join(binding.root, relative), nil
}

// readRecord reads one record of at most maximumRecordBytes. A record is a regular file reached without following a
// final link, as the installer requires of the records it reads (managed_installation.py:278-290).
func readRecord(kind RecordKind, path string) ([]byte, *RuntimeFailure) {
	file, failure := openRecord(kind, path)
	if failure != nil {
		return nil, failure
	}
	defer file.Close()
	raw, err := io.ReadAll(io.LimitReader(file, maximumRecordBytes+1))
	if err != nil {
		return nil, recordUnavailable(kind, AccessIO)
	}
	if len(raw) > maximumRecordBytes {
		return nil, recordInvalid(kind, DecodeTooLarge, "")
	}
	return raw, nil
}

// digestRecord streams one record of at most limit bytes through SHA-256.
func digestRecord(kind RecordKind, path string, limit int64) (Digest, *RuntimeFailure) {
	file, failure := openRecord(kind, path)
	if failure != nil {
		return Digest{}, failure
	}
	defer file.Close()
	hash := sha256.New()
	read, err := io.Copy(hash, io.LimitReader(file, limit+1))
	if err != nil {
		return Digest{}, recordUnavailable(kind, AccessIO)
	}
	if read > limit {
		return Digest{}, recordInvalid(kind, DecodeTooLarge, "")
	}
	var digest Digest
	hash.Sum(digest[:0])
	return digest, nil
}

func openRecord(kind RecordKind, path string) (*os.File, *RuntimeFailure) {
	// O_NONBLOCK keeps a FIFO in the record's place from blocking the open; fstat then rejects it.
	file, err := os.OpenFile(path, os.O_RDONLY|syscall.O_NOFOLLOW|syscall.O_NONBLOCK, 0)
	if err != nil {
		return nil, recordUnavailable(kind, accessCause(err))
	}
	info, err := file.Stat()
	if err != nil {
		file.Close()
		return nil, recordUnavailable(kind, AccessIO)
	}
	if !info.Mode().IsRegular() {
		file.Close()
		return nil, recordUnavailable(kind, AccessNotRegular)
	}
	return file, nil
}

func accessCause(err error) AccessCause {
	switch {
	case errors.Is(err, syscall.ELOOP):
		return AccessLinked
	case errors.Is(err, fs.ErrNotExist), errors.Is(err, syscall.ENOTDIR):
		return AccessNotFound
	case errors.Is(err, fs.ErrPermission):
		return AccessPermission
	default:
		return AccessIO
	}
}
