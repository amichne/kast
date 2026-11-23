package envelope

import (
	"slices"

	"github.com/amichne/kast/frontend/internal/lifecycle/codec"
	"github.com/amichne/kast/frontend/internal/lifecycle/record"
)

// HandoffVersions are the handoff schema versions this binary writes and reads.
var HandoffVersions = []int64{1}

// Operation is the closed set of transactions a handoff asks the target to perform.
type Operation uint8

const (
	OperationInstall Operation = iota + 1
	OperationUpgrade
	OperationRepair
)

var operationNames = map[Operation]string{
	OperationInstall: "INSTALL", OperationUpgrade: "UPGRADE", OperationRepair: "REPAIR",
}

// ParseOperation admits an operation name.
func ParseOperation(text string) (Operation, bool) { return parse(operationNames, text) }

func (o Operation) String() string { return operationNames[o] }

// MarshalText writes the operation name.
func (o Operation) MarshalText() ([]byte, error) { return marshal(operationNames, o) }

// OutputMode is the closed set of output forms.
type OutputMode uint8

const (
	OutputText OutputMode = iota + 1
	OutputJSON
)

var outputNames = map[OutputMode]string{OutputText: "TEXT", OutputJSON: "JSON"}

// ParseOutputMode admits an output name.
func ParseOutputMode(text string) (OutputMode, bool) { return parse(outputNames, text) }

func (o OutputMode) String() string { return outputNames[o] }

// MarshalText writes the output name.
func (o OutputMode) MarshalText() ([]byte, error) { return marshal(outputNames, o) }

// Selection is the resolved selection a handoff carries: the release tag, the policy, the explicit public version,
// and the source revision.
type Selection struct {
	identity record.Identity
	revision record.Revision
}

// NewSelection builds a selection.
func NewSelection(identity record.Identity, revision record.Revision) Selection {
	return Selection{identity, revision}
}

// Identity is the tag, policy and public version.
func (s Selection) Identity() record.Identity { return s.identity }

// SourceRevision is the build's source revision.
func (s Selection) SourceRevision() record.Revision { return s.revision }

// Handoff is handoff.json: written by the selecting binary and read once by the target.
type Handoff struct {
	schema    int64
	operation Operation
	selection Selection
	record    record.Digest
	control   record.Digest
	output    OutputMode
}

// NewHandoff builds a handoff at a schema version this binary writes.
func NewHandoff(schema int64, operation Operation, selection Selection, recordSHA256, controlSHA256 record.Digest,
	output OutputMode) (Handoff, bool) {
	if !slices.Contains(HandoffVersions, schema) || operationNames[operation] == "" || outputNames[output] == "" {
		return Handoff{}, false
	}
	return Handoff{schema, operation, selection, recordSHA256, controlSHA256, output}, true
}

// SchemaVersion is the handoff's schema version.
func (h Handoff) SchemaVersion() int64 { return h.schema }

// Operation is the requested transaction.
func (h Handoff) Operation() Operation { return h.operation }

// Selection is the resolved selection.
func (h Handoff) Selection() Selection { return h.selection }

// RecordSHA256 is the digest of the build record in the handoff directory.
func (h Handoff) RecordSHA256() record.Digest { return h.record }

// ControlSHA256 is the digest of the target executable.
func (h Handoff) ControlSHA256() record.Digest { return h.control }

// Output is the output form.
func (h Handoff) Output() OutputMode { return h.output }

type selectionWire struct {
	ReleaseTag     record.ReleaseTag `json:"releaseTag"`
	Policy         record.Policy     `json:"policy"`
	PublicVersion  *record.SemVer    `json:"publicVersion"`
	SourceRevision record.Revision   `json:"sourceRevision"`
}

type handoffWire struct {
	SchemaVersion int64         `json:"schemaVersion"`
	Operation     Operation     `json:"operation"`
	Selection     selectionWire `json:"selection"`
	RecordSHA256  record.Digest `json:"recordSha256"`
	ControlSHA256 record.Digest `json:"controlSha256"`
	Output        OutputMode    `json:"output"`
}

// Encode writes the canonical bytes.
func (h Handoff) Encode() []byte {
	identity := h.selection.identity
	encoded, err := codec.Encode(handoffWire{h.schema, h.operation, selectionWire{identity.Tag(), identity.Policy(),
		identity.PublicVersion(), h.selection.revision}, h.record, h.control, h.output})
	if err != nil {
		panic("envelope: an admitted handoff failed to encode: " + err.Error())
	}
	return encoded
}

// DecodeHandoff strictly decodes handoff.json. A schema version outside HandoffVersions is SchemaUnsupported.
func DecodeHandoff(raw []byte) (Handoff, error) {
	object, err := codec.Document(raw, record.MaximumRecordBytes)
	if err != nil {
		return Handoff{}, err
	}
	schema, err := object.SchemaVersion(HandoffVersions...)
	if err != nil {
		return Handoff{}, err
	}
	operation, err := codec.Text(object, "operation", ParseOperation)
	if err != nil {
		return Handoff{}, err
	}
	selectionObject, err := object.Object("selection")
	if err != nil {
		return Handoff{}, err
	}
	identity, err := record.DecodeIdentity(selectionObject)
	if err != nil {
		return Handoff{}, err
	}
	revision, err := codec.Text(selectionObject, "sourceRevision", record.ParseRevision)
	if err != nil {
		return Handoff{}, err
	}
	if err := selectionObject.Closed(); err != nil {
		return Handoff{}, err
	}
	recordSHA, err := codec.Text(object, "recordSha256", record.ParseDigest)
	if err != nil {
		return Handoff{}, err
	}
	controlSHA, err := codec.Text(object, "controlSha256", record.ParseDigest)
	if err != nil {
		return Handoff{}, err
	}
	output, err := codec.Text(object, "output", ParseOutputMode)
	if err != nil {
		return Handoff{}, err
	}
	if err := object.Closed(); err != nil {
		return Handoff{}, err
	}
	return Handoff{schema, operation, Selection{identity, revision}, recordSHA, controlSHA, output}, nil
}

func parse[E comparable](names map[E]string, text string) (E, bool) {
	for value, name := range names {
		if name == text {
			return value, true
		}
	}
	var zero E
	return zero, false
}

func marshal[E comparable](names map[E]string, value E) ([]byte, error) {
	name, ok := names[value]
	if !ok {
		return nil, codec.Reject(codec.InvalidValue, "")
	}
	return []byte(name), nil
}
