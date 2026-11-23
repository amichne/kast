package record

import (
	"math"
	"slices"
	"strings"

	"github.com/amichne/kast/frontend/internal/lifecycle/codec"
)

// RetirementSchemaVersion is the newest retirement document version this binary decodes.
const RetirementSchemaVersion = 1

// RetirementReason is the closed set of reasons a retirement does not complete. The document reasons mirror the
// Kotlin closed enums that retirement maps: container rejection, user-state cleanup (UserStateCleanupFailure) and
// the foreign login agent of decision D1. The last three are Go's own observations of the child and never appear in
// a document.
type RetirementReason uint8

const (
	ReasonContainerRejected RetirementReason = iota + 1
	ReasonContainerForeign
	ReasonOwnershipUnproven
	ReasonActiveHost
	ReasonCapacityExceeded
	ReasonFilesystemRejected
	ReasonPathRejected
	ReasonSchemaRejected
	ReasonRecordRejected
	ReasonWorkspaceMismatch
	ReasonUnsettledMutation
	ReasonCheckpointRequired
	ReasonStorageUnavailable
	ReasonLoginAgentForeign
	ReasonDocumentUnsupported
	ReasonDocumentInvalid
	ReasonDeadlineExceeded
)

var reasonNames = map[RetirementReason]string{
	ReasonContainerRejected: "CONTAINER_REJECTED", ReasonContainerForeign: "CONTAINER_FOREIGN",
	ReasonOwnershipUnproven: "OWNERSHIP_UNPROVEN", ReasonActiveHost: "ACTIVE_HOST",
	ReasonCapacityExceeded: "CAPACITY_EXCEEDED", ReasonFilesystemRejected: "FILESYSTEM_REJECTED",
	ReasonPathRejected: "PATH_REJECTED", ReasonSchemaRejected: "SCHEMA_REJECTED",
	ReasonRecordRejected: "RECORD_REJECTED", ReasonWorkspaceMismatch: "WORKSPACE_MISMATCH",
	ReasonUnsettledMutation: "UNSETTLED_MUTATION", ReasonCheckpointRequired: "CHECKPOINT_REQUIRED",
	ReasonStorageUnavailable: "STORAGE_UNAVAILABLE", ReasonLoginAgentForeign: "LOGIN_AGENT_FOREIGN",
	ReasonDocumentUnsupported: "DOCUMENT_UNSUPPORTED", ReasonDocumentInvalid: "DOCUMENT_INVALID",
	ReasonDeadlineExceeded: "DEADLINE_EXCEEDED",
}

// ParseRetirementReason admits any reason name.
func ParseRetirementReason(text string) (RetirementReason, bool) { return parseEnum(reasonNames, text) }

func (r RetirementReason) String() string { return reasonNames[r] }

// MarshalText writes the reason name.
func (r RetirementReason) MarshalText() ([]byte, error) { return marshalEnum(reasonNames, r) }

// InDocument reports whether the child may write the reason; Go's own observations may not.
func (r RetirementReason) InDocument() bool {
	return r >= ReasonContainerRejected && r <= ReasonLoginAgentForeign
}

// Removal reports whether the reason comes from Kotlin's removal checks (section 3.4), which map to REMOVAL_BLOCKED.
func (r RetirementReason) Removal() bool {
	return r >= ReasonContainerRejected && r <= ReasonStorageUnavailable
}

// ProcessID is a positive process identifier.
type ProcessID int32

// ParseProcessID admits a positive identifier within the range, before it is narrowed.
func ParseProcessID(value int64) (ProcessID, bool) {
	if value <= 0 || value > math.MaxInt32 {
		return 0, false
	}
	return ProcessID(value), true
}

// Incarnation is one process instance: its identifier and the start time the platform reports, in the platform's
// own unit. A different start time under the same identifier is a different process.
type Incarnation struct {
	PID       ProcessID `json:"pid"`
	StartTime int64     `json:"startTime"`
}

// RetirementDocument is the closed document managed-retire writes to stdout: either the facts it proved, or the one
// reason it stopped.
type RetirementDocument interface {
	retirement()
}

// Retired is a completed retirement: the semantic label proved absent, each broker label deactivated, and each Host
// incarnation proved exited.
type Retired struct {
	semantic ServiceLabel
	brokers  []ServiceLabel
	hosts    []Incarnation
}

func (Retired) retirement() {}

// Semantic is the semantic coordinator label.
func (r Retired) Semantic() ServiceLabel { return r.semantic }

// Brokers are the deactivated broker labels, sorted.
func (r Retired) Brokers() []ServiceLabel { return slices.Clone(r.brokers) }

// Hosts are the exited Host incarnations, sorted by identifier and start time.
func (r Retired) Hosts() []Incarnation { return slices.Clone(r.hosts) }

// RetirementRejected is a retirement the child stopped, with its closed reason.
type RetirementRejected struct{ reason RetirementReason }

func (RetirementRejected) retirement() {}

// Reason is the child's reason.
func (r RetirementRejected) Reason() RetirementReason { return r.reason }

// DecodeRetirementDocument strictly decodes a retirement document. A schemaVersion above the newest this binary
// knows is SchemaUnsupported, which the caller reports as RETIREMENT_REJECTED with DOCUMENT_UNSUPPORTED.
func DecodeRetirementDocument(raw []byte) (RetirementDocument, error) {
	object, err := codec.Document(raw, MaximumRecordBytes)
	if err != nil {
		return nil, err
	}
	if _, err := object.SchemaVersion(RetirementSchemaVersion); err != nil {
		return nil, err
	}
	outcome, err := object.String("outcome")
	if err != nil {
		return nil, err
	}
	switch outcome {
	case "RETIRED":
		document, err := decodeRetired(object)
		if err != nil {
			return nil, err
		}
		return document, object.Closed()
	case "REJECTED":
		reason, err := codec.Text(object, "reason", ParseRetirementReason)
		if err != nil {
			return nil, err
		}
		if !reason.InDocument() {
			return nil, codec.Reject(codec.InvalidValue, "reason")
		}
		return RetirementRejected{reason}, object.Closed()
	default:
		return nil, codec.Reject(codec.InvalidValue, "outcome")
	}
}

func decodeRetired(object codec.Object) (Retired, error) {
	semantic, err := codec.Text(object, "semanticLabel", ParseServiceLabel)
	if err != nil {
		return Retired{}, err
	}
	if semantic.Kind() != ServiceSemantic {
		return Retired{}, codec.Reject(codec.InvalidValue, "semanticLabel")
	}
	brokerValues, err := object.Array("brokerLabels")
	if err != nil {
		return Retired{}, err
	}
	brokers := make([]ServiceLabel, 0, len(brokerValues))
	for i, value := range brokerValues {
		member := codec.Element("brokerLabels", i)
		label, err := codec.ElementText(value, member, ParseServiceLabel)
		if err != nil {
			return Retired{}, err
		}
		if label.Kind() != ServiceBroker || (i > 0 && strings.Compare(brokers[i-1].String(), label.String()) >= 0) {
			return Retired{}, codec.Reject(codec.InvalidValue, member)
		}
		brokers = append(brokers, label)
	}
	hostValues, err := object.Array("hosts")
	if err != nil {
		return Retired{}, err
	}
	hosts := make([]Incarnation, 0, len(hostValues))
	for i, value := range hostValues {
		member := codec.Element("hosts", i)
		entry, err := codec.ObjectValue(value, member)
		if err != nil {
			return Retired{}, err
		}
		pid, err := entry.Int("pid")
		if err != nil {
			return Retired{}, err
		}
		start, err := entry.Int("startTime")
		if err != nil {
			return Retired{}, err
		}
		if err := entry.Closed(); err != nil {
			return Retired{}, err
		}
		process, ok := ParseProcessID(pid)
		host := Incarnation{process, start}
		if !ok || start < 0 || (i > 0 && !incarnationBefore(hosts[i-1], host)) {
			return Retired{}, codec.Reject(codec.InvalidValue, member)
		}
		hosts = append(hosts, host)
	}
	return Retired{semantic, brokers, hosts}, nil
}

func incarnationBefore(a, b Incarnation) bool {
	return a.PID < b.PID || (a.PID == b.PID && a.StartTime < b.StartTime)
}
