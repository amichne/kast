package frontend

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"reflect"
	"strings"
)

// The installed records the frontend reads to name the Host runtime. The installer is their single writer: it encodes
// each dataclass in field order with no whitespace and one trailing newline (managed_installation.py:272-275). These
// types declare their fields in that order, so a test can compare encoded shapes byte for byte.

// productSelection is share/kast/product-selection.json, written beside the payload at
// managed_product_installation.py:604-605 from managed_product.py:44-58 (ProductReference).
type productSelection struct {
	ReleaseTag            string   `json:"releaseTag"`
	PublicVersion         *string  `json:"publicVersion"`
	Version               string   `json:"version"`
	SourceRevision        Revision `json:"sourceRevision"`
	SourceTree            Revision `json:"sourceTree"`
	BuildRecordSHA256     Digest   `json:"buildRecordSha256"`
	Target                Target   `json:"target"`
	BundleSHA256          Digest   `json:"bundleSha256"`
	QualificationSHA256   Digest   `json:"qualificationSha256"`
	RuntimeRoot           string   `json:"runtimeRoot"`
	RuntimeArchiveSHA256  Digest   `json:"runtimeArchiveSha256"`
	RuntimeManifestSHA256 Digest   `json:"runtimeManifestSha256"`
	Policy                Policy   `json:"policy"`
	SchemaVersion         int      `json:"schemaVersion"`
}

// runtimeReceipt is runtime.json at the root of a runtime entry, written at managed_product_installation.py:183-191
// from managed_product.py:36-40 (RuntimeReceipt).
type runtimeReceipt struct {
	ArchiveSHA256  Digest `json:"archiveSha256"`
	ManifestSHA256 Digest `json:"manifestSha256"`
	SchemaVersion  int    `json:"schemaVersion"`
}

const (
	productSelectionSchema = 1
	runtimeReceiptSchema   = 1
	// maximumRecordBytes is the installer's own read bound for these records (managed_installation.py:278).
	maximumRecordBytes = 65_536
	// maximumManifestBytes is the installer's bound for a runtime manifest (managed_product.py:23).
	maximumManifestBytes = 16_777_216
	// maximumTextBytes bounds each free text field, as the installer's strict decoder does.
	maximumTextBytes = 4096
)

// Digest is a SHA-256 digest. It encodes as 64 lowercase hexadecimal characters.
type Digest [32]byte

func (d Digest) String() string { return hex.EncodeToString(d[:]) }

func (d Digest) MarshalJSON() ([]byte, error) { return json.Marshal(d.String()) }

func (d *Digest) UnmarshalJSON(raw []byte) error {
	var text string
	if json.Unmarshal(raw, &text) != nil || !lowerHex(text, 2*len(d)) {
		return invalidValue(raw, d)
	}
	_, err := hex.Decode(d[:], []byte(text))
	return err
}

// Revision is a Git object name: 40 lowercase hexadecimal characters that are not all zero.
type Revision string

func (r *Revision) UnmarshalJSON(raw []byte) error {
	var text string
	if json.Unmarshal(raw, &text) != nil || !lowerHex(text, 40) || text == strings.Repeat("0", 40) {
		return invalidValue(raw, r)
	}
	*r = Revision(text)
	return nil
}

// Target is the closed set of product targets: the only targets a product record carries (managed_release.py:490).
type Target uint8

const (
	TargetMacOSAArch64 Target = iota + 1
	TargetLinuxX8664
)

var targetNames = map[Target]string{TargetMacOSAArch64: "macos-aarch64", TargetLinuxX8664: "linux-x86_64"}

func (t Target) String() string { return targetNames[t] }

func (t Target) MarshalJSON() ([]byte, error) { return marshalName(targetNames, t) }

func (t *Target) UnmarshalJSON(raw []byte) error { return unmarshalName(targetNames, raw, t) }

// parseTarget names the target for a public platform name.
func parseTarget(name string) (Target, bool) { return nameOf(targetNames, name) }

// Policy is the closed set of selection policies (managed_release.py:913).
type Policy uint8

const (
	PolicyTrackStable Policy = iota + 1
	PolicyTrackMain
	PolicyPinnedBuild
)

var policyNames = map[Policy]string{
	PolicyTrackStable: "TrackStable", PolicyTrackMain: "TrackMain", PolicyPinnedBuild: "PinnedBuild",
}

func (p Policy) MarshalJSON() ([]byte, error) { return marshalName(policyNames, p) }

func (p *Policy) UnmarshalJSON(raw []byte) error { return unmarshalName(policyNames, raw, p) }

// decodeProductSelection admits a product selection record. Free text fields are bounded and non-empty; the runtime
// root must be absolute, and its agreement with the runtime binding is checked by bindRuntime.
func decodeProductSelection(raw []byte) (productSelection, *RuntimeFailure) {
	selection, failure := decodeRecord[productSelection](RecordProductSelection, raw, productSelectionSchema)
	if failure != nil {
		return productSelection{}, failure
	}
	for _, text := range []struct {
		field string
		value *string
	}{
		{"releaseTag", &selection.ReleaseTag},
		{"publicVersion", selection.PublicVersion},
		{"version", &selection.Version},
		{"runtimeRoot", &selection.RuntimeRoot},
	} {
		if text.value != nil && (*text.value == "" || len(*text.value) > maximumTextBytes) {
			return productSelection{}, recordInvalid(RecordProductSelection, DecodeFieldValue, RecordField(text.field))
		}
	}
	if !strings.HasPrefix(selection.RuntimeRoot, "/") {
		return productSelection{}, recordInvalid(RecordProductSelection, DecodeFieldValue, "runtimeRoot")
	}
	return selection, nil
}

// decodeRuntimeReceipt admits a runtime entry's receipt.
func decodeRuntimeReceipt(raw []byte) (runtimeReceipt, *RuntimeFailure) {
	return decodeRecord[runtimeReceipt](RecordRuntimeReceipt, raw, runtimeReceiptSchema)
}

// decodeRecord admits raw as exactly one flat JSON object holding each field of T once, and decodes it into T. The
// schema version is read first, so a record from a newer writer is reported as such and not as an unknown field.
// Every field is required; only a pointer field admits an explicit null.
func decodeRecord[T any](kind RecordKind, raw []byte, schema int) (T, *RuntimeFailure) {
	var zero T
	if len(raw) > maximumRecordBytes {
		return zero, recordInvalid(kind, DecodeTooLarge, "")
	}
	keys, values, cause := recordMembers(raw)
	if cause != 0 {
		return zero, recordInvalid(kind, cause, "")
	}
	version, present := values["schemaVersion"]
	if !present {
		return zero, recordInvalid(kind, DecodeMissingField, "schemaVersion")
	}
	var observed int
	if isNull(version) || json.Unmarshal(version, &observed) != nil {
		return zero, recordInvalid(kind, DecodeFieldValue, "schemaVersion")
	}
	if observed != schema {
		return zero, recordSchemaUnsupported(kind, observed)
	}
	fields := recordFields(reflect.TypeFor[T]())
	for _, key := range keys {
		if _, declared := fields[key]; !declared {
			return zero, recordInvalid(kind, DecodeUnknownField, "")
		}
	}
	for _, field := range declaredFields(reflect.TypeFor[T]()) {
		value, present := values[field]
		if !present {
			return zero, recordInvalid(kind, DecodeMissingField, RecordField(field))
		}
		if isNull(value) && !fields[field] {
			return zero, recordInvalid(kind, DecodeFieldValue, RecordField(field))
		}
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	var record T
	if decoder.Decode(&record) != nil {
		return zero, recordInvalid(kind, DecodeFieldValue, rejectedField[T](values))
	}
	return record, nil
}

// rejectedField names the first declared field whose own value does not decode. The standard decoder does not name
// the field when a field type's own decoder rejects the value, so each value is decoded alone.
func rejectedField[T any](values map[string]json.RawMessage) RecordField {
	var probe T
	record := reflect.ValueOf(&probe).Elem()
	for i, name := range declaredFields(record.Type()) {
		if json.Unmarshal(values[name], record.Field(i).Addr().Interface()) != nil {
			return RecordField(name)
		}
	}
	return ""
}

// recordMembers splits one top-level JSON object into its keys, in order, and their raw values. Duplicate keys and any
// data after the object are rejected; the standard decoder would otherwise keep the last duplicate silently.
func recordMembers(raw []byte) ([]string, map[string]json.RawMessage, DecodeCause) {
	decoder := json.NewDecoder(bytes.NewReader(raw))
	open, err := decoder.Token()
	if err != nil {
		return nil, nil, DecodeSyntax
	}
	if open != json.Delim('{') {
		return nil, nil, DecodeNotObject
	}
	var keys []string
	values := map[string]json.RawMessage{}
	for decoder.More() {
		token, err := decoder.Token()
		key, isKey := token.(string)
		if err != nil || !isKey {
			return nil, nil, DecodeSyntax
		}
		var value json.RawMessage
		if decoder.Decode(&value) != nil {
			return nil, nil, DecodeSyntax
		}
		if _, duplicate := values[key]; duplicate {
			return nil, nil, DecodeDuplicateKey
		}
		keys = append(keys, key)
		values[key] = value
	}
	if closing, err := decoder.Token(); err != nil || closing != json.Delim('}') {
		return nil, nil, DecodeSyntax
	}
	if _, err := decoder.Token(); err != io.EOF {
		return nil, nil, DecodeTrailingData
	}
	return keys, values, 0
}

// declaredFields lists the JSON names of a record's fields in declaration order.
func declaredFields(record reflect.Type) []string {
	names := make([]string, record.NumField())
	for i := range names {
		names[i] = record.Field(i).Tag.Get("json")
	}
	return names
}

// recordFields maps each JSON name of a record to whether that field admits an explicit null.
func recordFields(record reflect.Type) map[string]bool {
	fields := make(map[string]bool, record.NumField())
	for i := range record.NumField() {
		field := record.Field(i)
		fields[field.Tag.Get("json")] = field.Type.Kind() == reflect.Pointer
	}
	return fields
}

func isNull(raw json.RawMessage) bool { return bytes.Equal(bytes.TrimSpace(raw), []byte("null")) }

func lowerHex(text string, length int) bool {
	if len(text) != length {
		return false
	}
	for _, character := range []byte(text) {
		if (character < '0' || character > '9') && (character < 'a' || character > 'f') {
			return false
		}
	}
	return true
}

// invalidValue is the decoder's own type error, so the standard decoder records which field held the value.
func invalidValue(raw []byte, target any) error {
	return &json.UnmarshalTypeError{Value: string(bytes.TrimSpace(raw)), Type: reflect.TypeOf(target).Elem()}
}

func marshalName[K comparable](names map[K]string, value K) ([]byte, error) {
	name, known := names[value]
	if !known {
		return nil, errors.New("unnamed enumeration value")
	}
	return json.Marshal(name)
}

func unmarshalName[K comparable](names map[K]string, raw []byte, target *K) error {
	var text string
	if json.Unmarshal(raw, &text) != nil {
		return invalidValue(raw, target)
	}
	value, known := nameOf(names, text)
	if !known {
		return invalidValue(raw, target)
	}
	*target = value
	return nil
}

func nameOf[K comparable](names map[K]string, name string) (K, bool) {
	for value, candidate := range names {
		if candidate == name {
			return value, true
		}
	}
	var zero K
	return zero, false
}
