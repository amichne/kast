package failure

import (
	"encoding/json"
	"slices"

	"github.com/amichne/kast/frontend/internal/lifecycle/codec"
	"github.com/amichne/kast/frontend/internal/lifecycle/record"
)

// OutcomeKind is the closed union of successful lifecycle outcomes. It is separate from Code: ALREADY_CURRENT is a
// success, not a failure.
type OutcomeKind uint8

const (
	Installed OutcomeKind = iota + 1
	Upgraded
	Repaired
	AlreadyCurrent
	Uninstalled
	UninstalledWithoutUserState
)

var outcomeNames = names[OutcomeKind]{
	Installed: "INSTALLED", Upgraded: "UPGRADED", Repaired: "REPAIRED", AlreadyCurrent: "ALREADY_CURRENT",
	Uninstalled: "UNINSTALLED", UninstalledWithoutUserState: "UNINSTALLED_WITHOUT_USER_STATE",
}

func (o OutcomeKind) String() string               { return outcomeNames[o] }
func (o OutcomeKind) MarshalText() ([]byte, error) { return outcomeNames.marshal(o) }

// Observation is a sealed fact an outcome reports.
type Observation interface {
	observation()
}

// SupervisorUnavailable: the user has no service supervisor, so no coordinator could run.
type SupervisorUnavailable struct{}

// ResidueRetained: a tree being deleted could not be fully deleted and stays, inert, under its removing name.
type ResidueRetained struct{ Removing record.VersionID }

// LoginAgentRetained: a login agent that does not match its exact owned document was left in place.
type LoginAgentRetained struct{ Label record.ServiceLabel }

func (SupervisorUnavailable) observation() {}
func (ResidueRetained) observation()       {}
func (LoginAgentRetained) observation()    {}

// MarshalJSON writes the variant with its discriminator.
func (SupervisorUnavailable) MarshalJSON() ([]byte, error) {
	return json.Marshal(struct {
		Kind string `json:"kind"`
	}{"SUPERVISOR_UNAVAILABLE"})
}

// MarshalJSON writes the variant with its discriminator and the removing name.
func (r ResidueRetained) MarshalJSON() ([]byte, error) {
	return json.Marshal(struct {
		Kind string `json:"kind"`
		Name string `json:"name"`
	}{"RESIDUE_RETAINED", ".kast-removing-" + r.Removing.String()})
}

// MarshalJSON writes the variant with its discriminator and the agent's label.
func (l LoginAgentRetained) MarshalJSON() ([]byte, error) {
	return json.Marshal(struct {
		Kind  string              `json:"kind"`
		Label record.ServiceLabel `json:"label"`
	}{"LOGIN_AGENT_RETAINED", l.Label})
}

// Outcome is one successful lifecycle result with the observations it carries.
type Outcome struct {
	kind         OutcomeKind
	observations []Observation
}

// NewOutcome builds an outcome; observations keep their order.
func NewOutcome(kind OutcomeKind, observations ...Observation) Outcome {
	if outcomeNames[kind] == "" {
		panic("failure: unknown outcome")
	}
	return Outcome{kind, slices.Clone(observations)}
}

// Kind is the outcome.
func (o Outcome) Kind() OutcomeKind { return o.kind }

// Observations are the reported facts.
func (o Outcome) Observations() []Observation { return slices.Clone(o.observations) }

type outcomeWire struct {
	SchemaVersion int           `json:"schemaVersion"`
	Outcome       OutcomeKind   `json:"outcome"`
	Observations  []Observation `json:"observations"`
}

// Encode writes the outcome document's canonical bytes.
func (o Outcome) Encode() []byte {
	observations := o.observations
	if observations == nil {
		observations = []Observation{}
	}
	encoded, err := codec.Encode(outcomeWire{DocumentSchemaVersion, o.kind, observations})
	if err != nil {
		panic("failure: an admitted outcome failed to encode: " + err.Error())
	}
	return encoded
}
