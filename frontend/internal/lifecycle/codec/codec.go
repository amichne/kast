// Package codec is the one JSON boundary of the lifecycle core. It admits exactly one JSON object with unique member
// names and no trailing data, exposes its members by exact name with absence and null kept distinct, and writes the
// canonical form of a typed value. It performs no effects.
package codec

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"strconv"
	"unicode/utf8"
)

// Cause is the closed set of reasons a document is not admitted.
type Cause uint8

const (
	// Malformed is bytes that are not one well-formed JSON document in UTF-8.
	Malformed Cause = iota + 1
	// TooLarge is a document longer than its contract's byte limit.
	TooLarge
	// NotObject is a document, or a member, that must be an object and is not.
	NotObject
	// DuplicateMember is an object that names one member twice, at any depth.
	DuplicateMember
	// TrailingData is any non-space byte after the document.
	TrailingData
	// MissingMember is a required member that is absent.
	MissingMember
	// NullMember is a member that is null where the contract requires a value.
	NullMember
	// UnknownMember is a member that a strict contract does not define.
	UnknownMember
	// InvalidValue is a member whose JSON type or value breaks the contract's value rule.
	InvalidValue
	// SchemaUnsupported is an integer schemaVersion that this binary does not decode.
	SchemaUnsupported
)

var causeNames = map[Cause]string{
	Malformed: "MALFORMED", TooLarge: "TOO_LARGE", NotObject: "NOT_OBJECT", DuplicateMember: "DUPLICATE_MEMBER",
	TrailingData: "TRAILING_DATA", MissingMember: "MISSING_MEMBER", NullMember: "NULL_MEMBER",
	UnknownMember: "UNKNOWN_MEMBER", InvalidValue: "INVALID_VALUE", SchemaUnsupported: "SCHEMA_UNSUPPORTED",
}

func (c Cause) String() string { return causeNames[c] }

// Error is a rejected document: its cause and the contract-defined member it concerns, empty for the whole document.
type Error struct {
	Cause  Cause
	Member string
}

func (e *Error) Error() string {
	if e.Member == "" {
		return "document rejected: " + e.Cause.String()
	}
	return "document rejected: " + e.Cause.String() + " at " + e.Member
}

// Reject builds an Error. Decoders return it for every value rule they check.
func Reject(cause Cause, member string) error { return &Error{Cause: cause, Member: member} }

// CauseOf returns the cause of a codec error, and false for any other error.
func CauseOf(err error) (Cause, bool) {
	var rejected *Error
	if errors.As(err, &rejected) {
		return rejected.Cause, true
	}
	return 0, false
}

// Object is one admitted JSON object: unique member names, each with its raw value.
type Object struct {
	path    string
	members map[string]json.RawMessage
	used    map[string]bool
}

// Document admits raw as exactly one JSON object of at most limit bytes, with valid UTF-8, unique member names at
// every depth, and nothing but white space after it.
func Document(raw []byte, limit int) (Object, error) {
	if len(raw) > limit {
		return Object{}, Reject(TooLarge, "")
	}
	if !utf8.Valid(raw) {
		return Object{}, Reject(Malformed, "")
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
	first, err := decoder.Token()
	if err != nil {
		return Object{}, Reject(Malformed, "")
	}
	if first != json.Delim('{') {
		return Object{}, Reject(NotObject, "")
	}
	if err := scanObject(decoder); err != nil {
		return Object{}, err
	}
	if _, err := decoder.Token(); err != io.EOF {
		return Object{}, Reject(TrailingData, "")
	}
	return parseObject(json.RawMessage(raw), "")
}

// scanObject consumes the members of an object whose opening brace was read, rejecting a repeated name.
func scanObject(decoder *json.Decoder) error {
	seen := map[string]bool{}
	for decoder.More() {
		token, err := decoder.Token()
		if err != nil {
			return Reject(Malformed, "")
		}
		name, ok := token.(string)
		if !ok {
			return Reject(Malformed, "")
		}
		if seen[name] {
			return Reject(DuplicateMember, name)
		}
		seen[name] = true
		if err := scanValue(decoder); err != nil {
			return err
		}
	}
	if _, err := decoder.Token(); err != nil {
		return Reject(Malformed, "")
	}
	return nil
}

func scanValue(decoder *json.Decoder) error {
	token, err := decoder.Token()
	if err != nil {
		return Reject(Malformed, "")
	}
	switch token {
	case json.Delim('{'):
		return scanObject(decoder)
	case json.Delim('['):
		for decoder.More() {
			if err := scanValue(decoder); err != nil {
				return err
			}
		}
		if _, err := decoder.Token(); err != nil {
			return Reject(Malformed, "")
		}
	}
	return nil
}

func parseObject(raw json.RawMessage, path string) (Object, error) {
	members := map[string]json.RawMessage{}
	if err := json.Unmarshal(raw, &members); err != nil {
		return Object{}, Reject(NotObject, path)
	}
	return Object{path: path, members: members, used: map[string]bool{}}, nil
}

func (o Object) member(name string) string {
	if o.path == "" {
		return name
	}
	return o.path + "." + name
}

// Presence distinguishes an absent member, an explicit null, and a value.
type Presence uint8

const (
	Absent Presence = iota + 1
	Null
	Present
)

// Lookup returns a member's presence and raw value, and marks it read.
func (o Object) Lookup(name string) (Presence, json.RawMessage) {
	raw, ok := o.members[name]
	if !ok {
		return Absent, nil
	}
	o.used[name] = true
	if bytes.Equal(bytes.TrimSpace(raw), []byte("null")) {
		return Null, nil
	}
	return Present, raw
}

// Required returns a member that must be present and not null.
func (o Object) Required(name string) (json.RawMessage, error) {
	switch presence, raw := o.Lookup(name); presence {
	case Absent:
		return nil, Reject(MissingMember, o.member(name))
	case Null:
		return nil, Reject(NullMember, o.member(name))
	default:
		return raw, nil
	}
}

// Nullable returns a member that must be present and may be null; a nil result is the explicit null.
func (o Object) Nullable(name string) (json.RawMessage, error) {
	switch presence, raw := o.Lookup(name); presence {
	case Absent:
		return nil, Reject(MissingMember, o.member(name))
	case Null:
		return nil, nil
	default:
		return raw, nil
	}
}

// Closed rejects any member that was not read: the strict contracts end with it.
func (o Object) Closed() error {
	for name := range o.members {
		if !o.used[name] {
			return Reject(UnknownMember, o.member(name))
		}
	}
	return nil
}

// Member names a member for a value-rule rejection.
func (o Object) Member(name string) string { return o.member(name) }

// String reads a required string member.
func (o Object) String(name string) (string, error) {
	raw, err := o.Required(name)
	if err != nil {
		return "", err
	}
	return StringValue(raw, o.member(name))
}

// StringValue reads a raw JSON string.
func StringValue(raw json.RawMessage, member string) (string, error) {
	var value string
	if len(raw) == 0 || raw[0] != '"' || json.Unmarshal(raw, &value) != nil {
		return "", Reject(InvalidValue, member)
	}
	return value, nil
}

// Int reads a required integer member: a JSON number with no fraction or exponent, within int64.
func (o Object) Int(name string) (int64, error) {
	raw, err := o.Required(name)
	if err != nil {
		return 0, err
	}
	return IntValue(raw, o.member(name))
}

// IntValue reads a raw JSON integer.
func IntValue(raw json.RawMessage, member string) (int64, error) {
	text := string(raw)
	if text == "" || bytes.ContainsAny(raw, ".eE+") {
		return 0, Reject(InvalidValue, member)
	}
	value, err := strconv.ParseInt(text, 10, 64)
	if err != nil || (text != "0" && (text[0] == '0' || (len(text) > 1 && text[:2] == "-0"))) {
		return 0, Reject(InvalidValue, member)
	}
	return value, nil
}

// Bool reads a required boolean member.
func (o Object) Bool(name string) (bool, error) {
	raw, err := o.Required(name)
	if err != nil {
		return false, err
	}
	switch string(raw) {
	case "true":
		return true, nil
	case "false":
		return false, nil
	default:
		return false, Reject(InvalidValue, o.member(name))
	}
}

// Object reads a required object member.
func (o Object) Object(name string) (Object, error) {
	raw, err := o.Required(name)
	if err != nil {
		return Object{}, err
	}
	return ObjectValue(raw, o.member(name))
}

// ObjectValue reads a raw JSON object; uniqueness was already proven by Document.
func ObjectValue(raw json.RawMessage, member string) (Object, error) {
	if len(raw) == 0 || raw[0] != '{' {
		return Object{}, Reject(NotObject, member)
	}
	return parseObject(raw, member)
}

// Array reads a required array member as its raw elements.
func (o Object) Array(name string) ([]json.RawMessage, error) {
	raw, err := o.Required(name)
	if err != nil {
		return nil, err
	}
	return ArrayValue(raw, o.member(name))
}

// ArrayValue reads a raw JSON array.
func ArrayValue(raw json.RawMessage, member string) ([]json.RawMessage, error) {
	var elements []json.RawMessage
	if len(raw) == 0 || raw[0] != '[' || json.Unmarshal(raw, &elements) != nil {
		return nil, Reject(InvalidValue, member)
	}
	return elements, nil
}

// Element names an array element for a rejection.
func Element(member string, index int) string { return member + "[" + strconv.Itoa(index) + "]" }

// Members returns the member names of an object in no particular order, and marks them read. Only map-shaped
// contract members use it.
func (o Object) Members() []string {
	names := make([]string, 0, len(o.members))
	for name := range o.members {
		o.used[name] = true
		names = append(names, name)
	}
	return names
}

// SchemaVersion reads the schemaVersion member first: it must be an integer, and only supported values are admitted.
func (o Object) SchemaVersion(supported ...int64) (int64, error) {
	version, err := o.Int("schemaVersion")
	if err != nil {
		return 0, err
	}
	for _, candidate := range supported {
		if version == candidate {
			return version, nil
		}
	}
	return 0, Reject(SchemaUnsupported, "schemaVersion")
}

// Text reads a required string member and admits it through parse.
func Text[T any](object Object, member string, parse func(string) (T, bool)) (T, error) {
	var zero T
	value, err := object.String(member)
	if err != nil {
		return zero, err
	}
	admitted, ok := parse(value)
	if !ok {
		return zero, Reject(InvalidValue, object.Member(member))
	}
	return admitted, nil
}

// NullableText reads a present member that may be an explicit null; nil is the null.
func NullableText[T any](object Object, member string, parse func(string) (T, bool)) (*T, error) {
	raw, err := object.Nullable(member)
	if err != nil || raw == nil {
		return nil, err
	}
	admitted, err := ElementText(raw, object.Member(member), parse)
	if err != nil {
		return nil, err
	}
	return &admitted, nil
}

// ElementText reads one raw string value and admits it through parse.
func ElementText[T any](raw json.RawMessage, member string, parse func(string) (T, bool)) (T, error) {
	var zero T
	value, err := StringValue(raw, member)
	if err != nil {
		return zero, err
	}
	admitted, ok := parse(value)
	if !ok {
		return zero, Reject(InvalidValue, member)
	}
	return admitted, nil
}

// Encode writes the canonical form of a typed value: compact JSON in field declaration order, without HTML escaping,
// followed by one newline. Every field is written; a nil slice is a defect the caller prevents.
func Encode(value any) ([]byte, error) {
	var buffer bytes.Buffer
	encoder := json.NewEncoder(&buffer)
	encoder.SetEscapeHTML(false)
	if err := encoder.Encode(value); err != nil {
		return nil, err
	}
	return buffer.Bytes(), nil
}
