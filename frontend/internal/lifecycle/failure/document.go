package failure

import (
	"math"

	"github.com/amichne/kast/frontend/internal/lifecycle/codec"
	"github.com/amichne/kast/frontend/internal/lifecycle/record"
)

// DocumentSchemaVersion is the failure and outcome document version.
const DocumentSchemaVersion = 1

type failureWire struct {
	SchemaVersion int      `json:"schemaVersion"`
	Code          Code     `json:"code"`
	Stage         Stage    `json:"stage"`
	Evidence      evidence `json:"evidence"`
}

// Encode writes the failure document's canonical bytes.
func (f Failure) Encode() []byte {
	encoded, err := codec.Encode(failureWire{DocumentSchemaVersion, f.code, f.stage, f.evidence})
	if err != nil {
		panic("failure: an admitted failure failed to encode: " + err.Error())
	}
	return encoded
}

// DecodeFailure strictly decodes a failure document. It reads the code first and decodes the evidence as that code's
// variant, so evidence of another shape is rejected.
func DecodeFailure(raw []byte) (Failure, error) {
	object, err := codec.Document(raw, record.MaximumRecordBytes)
	if err != nil {
		return Failure{}, err
	}
	if _, err := object.SchemaVersion(DocumentSchemaVersion); err != nil {
		return Failure{}, err
	}
	code, err := codec.Text(object, "code", codeNames.parse)
	if err != nil {
		return Failure{}, err
	}
	stage, err := codec.Text(object, "stage", parseStage)
	if err != nil {
		return Failure{}, err
	}
	evidenceObject, err := object.Object("evidence")
	if err != nil {
		return Failure{}, err
	}
	value, err := decodeEvidence(evidenceOf[code], evidenceObject)
	if err != nil {
		return Failure{}, err
	}
	if err := evidenceObject.Closed(); err != nil {
		return Failure{}, err
	}
	if err := object.Closed(); err != nil {
		return Failure{}, err
	}
	if !value.valid() || !reasonFits(code, value) {
		return Failure{}, codec.Reject(codec.InvalidValue, "evidence")
	}
	return Failure{code, stage, value}, nil
}

func decodeEvidence(kind EvidenceKind, object codec.Object) (evidence, error) {
	switch kind {
	case KindNone:
		return NoEvidence{}, nil
	case KindAssetDigest:
		asset, err := codec.Text(object, "asset", record.ParseAssetName)
		if err != nil {
			return nil, err
		}
		expected, err := codec.Text(object, "expected", record.ParseDigest)
		if err != nil {
			return nil, err
		}
		observed, err := codec.Text(object, "observed", record.ParseDigest)
		return AssetDigestEvidence{asset, expected, observed}, err
	case KindPath:
		root, path, err := decodeLocation(object)
		return PathEvidence{root, path}, err
	case KindFilesystem:
		root, path, err := decodeLocation(object)
		if err != nil {
			return nil, err
		}
		operation, err := codec.Text(object, "operation", operationNames.parse)
		if err != nil {
			return nil, err
		}
		cause, err := codec.Text(object, "cause", fsCauseNames.parse)
		if err != nil {
			return nil, err
		}
		errno, err := boundedInt(object, "errno", 0, MaximumErrno)
		return FilesystemEvidence{root, path, operation, cause, errno}, err
	case KindTransfer:
		asset, err := codec.Text(object, "asset", record.ParseAssetName)
		if err != nil {
			return nil, err
		}
		cause, err := codec.Text(object, "cause", transferNames.parse)
		if err != nil {
			return nil, err
		}
		status, err := boundedInt(object, "httpStatus", 0, maximumHTTPStatus)
		return TransferEvidence{asset, cause, status}, err
	case KindHolder:
		lockRange, err := codec.Text(object, "range", rangeNames.parse)
		if err != nil {
			return nil, err
		}
		mode, err := codec.Text(object, "mode", lockModeNames.parse)
		if err != nil {
			return nil, err
		}
		holder, err := boundedInt(object, "holder", 0, 1<<31-1)
		return HolderEvidence{lockRange, mode, record.ProcessID(holder)}, err
	case KindProcess:
		holder, err := boundedInt(object, "holder", 1, 1<<31-1)
		if err != nil {
			return nil, err
		}
		use, err := codec.Text(object, "use", useNames.parse)
		return ProcessEvidence{record.ProcessID(holder), use}, err
	case KindChild:
		exit, err := boundedInt(object, "exitCode", -1, 255)
		if err != nil {
			return nil, err
		}
		reason, err := codec.Text(object, "reason", record.ParseRetirementReason)
		return ChildEvidence{ExitCode{exit}, reason}, err
	case KindSupervisor:
		supervisor, err := codec.Text(object, "supervisor", supervisorNames.parse)
		if err != nil {
			return nil, err
		}
		label, err := codec.Text(object, "label", record.ParseServiceLabel)
		if err != nil {
			return nil, err
		}
		state, err := codec.Text(object, "state", unitStateNames.parse)
		return SupervisorEvidence{supervisor, label, state}, err
	default:
		return nil, codec.Reject(codec.InvalidValue, "code")
	}
}

func decodeLocation(object codec.Object) (record.RootKind, record.RelativePath, error) {
	root, err := codec.Text(object, "root", record.ParseRootKind)
	if err != nil {
		return 0, record.RelativePath{}, err
	}
	path, err := codec.Text(object, "path", record.ParseRelativePath)
	return root, path, err
}

// boundedInt reads an integer within the caller's bounds, and always within 32 bits, before it is narrowed.
func boundedInt(object codec.Object, member string, low, high int64) (int, error) {
	value, err := object.Int(member)
	if err != nil {
		return 0, err
	}
	if value < low || value > high || value < math.MinInt32 || value > math.MaxInt32 {
		return 0, codec.Reject(codec.InvalidValue, object.Member(member))
	}
	return int(value), nil
}
