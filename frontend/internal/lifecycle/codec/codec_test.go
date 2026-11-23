package codec

import (
	"strings"
	"testing"
)

func cause(t *testing.T, err error) Cause {
	t.Helper()
	value, ok := CauseOf(err)
	if !ok {
		t.Fatalf("not a codec error: %v", err)
	}
	return value
}

func TestDocumentAdmitsExactlyOneObject(t *testing.T) {
	cases := map[string]struct {
		raw  string
		want Cause
	}{
		"nested duplicate":        {`{"a":{"b":1,"b":2}}`, DuplicateMember},
		"duplicate inside array":  {`{"a":[{"b":1},{"c":1,"c":2}]}`, DuplicateMember},
		"trailing object":         {`{"a":1}{"a":1}`, TrailingData},
		"trailing garbage":        {`{"a":1} x`, TrailingData},
		"array document":          {`[1]`, NotObject},
		"string document":         {`"a"`, NotObject},
		"empty":                   {``, Malformed},
		"unterminated":            {`{"a":1`, Malformed},
		"invalid escape position": {"{\"a\":\"\xc3\x28\"}", Malformed},
	}
	for name, c := range cases {
		if _, err := Document([]byte(c.raw), 1024); err == nil || cause(t, err) != c.want {
			t.Errorf("%s: got %v, want %v", name, err, c.want)
		}
	}
	if _, err := Document([]byte(`{"a":1}`), 6); cause(t, err) != TooLarge {
		t.Errorf("limit not enforced: %v", err)
	}
	if _, err := Document([]byte(" \n{\"a\" : 1 }\n\t"), 1024); err != nil {
		t.Errorf("surrounding white space rejected: %v", err)
	}
}

func TestNestingIsBounded(t *testing.T) {
	for _, pair := range [][2]string{{"[", "]"}, {`{"a":`, "}"}} {
		deep := `{"a":` + strings.Repeat(pair[0], 100_000) + "1" + strings.Repeat(pair[1], 100_000) + "}"
		if _, err := Document([]byte(deep), 1<<20); cause(t, err) != Malformed {
			t.Errorf("deep %s admitted: %v", pair[0], err)
		}
		shallow := `{"a":` + strings.Repeat(pair[0], 6) + "1" + strings.Repeat(pair[1], 6) + "}"
		if _, err := Document([]byte(shallow), 1024); err != nil {
			t.Errorf("shallow %s rejected: %v", pair[0], err)
		}
	}
}

func TestMembersKeepAbsenceNullAndValueDistinct(t *testing.T) {
	object, err := Document([]byte(`{ "nil" : null , "value" : "x" , "number" : 12 }`), 1024)
	if err != nil {
		t.Fatal(err)
	}
	if presence, _ := object.Lookup("missing"); presence != Absent {
		t.Error("absent member reported present")
	}
	if presence, _ := object.Lookup("nil"); presence != Null {
		t.Error("null member reported as a value")
	}
	if _, err := object.Required("nil"); cause(t, err) != NullMember {
		t.Error("required null admitted")
	}
	if raw, err := object.Nullable("nil"); err != nil || raw != nil {
		t.Error("nullable null not reported as null")
	}
	if _, err := object.Nullable("missing"); cause(t, err) != MissingMember {
		t.Error("nullable absent member admitted")
	}
	if value, err := object.String("value"); err != nil || value != "x" {
		t.Errorf("string %q, %v", value, err)
	}
	if _, err := object.Int("value"); cause(t, err) != InvalidValue {
		t.Error("string admitted as an integer")
	}
	if value, err := object.Int("number"); err != nil || value != 12 {
		t.Errorf("integer %d, %v", value, err)
	}
	if err := object.Closed(); err != nil {
		t.Errorf("every member was read: %v", err)
	}
}

func TestIntegersHaveOneSpelling(t *testing.T) {
	for _, text := range []string{"1.0", "1e0", "1E2", "-0", `"1"`, "true", "9223372036854775808"} {
		if _, err := IntValue([]byte(text), "n"); err == nil {
			t.Errorf("%s admitted", text)
		}
	}
	for text, want := range map[string]int64{"0": 0, "-5": -5, "42": 42} {
		if value, err := IntValue([]byte(text), "n"); err != nil || value != want {
			t.Errorf("%s: %d, %v", text, value, err)
		}
	}
}

func TestClosedRejectsUnreadMembers(t *testing.T) {
	object, err := Document([]byte(`{"known":1,"Known":2}`), 1024)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = object.Int("known")
	if err := object.Closed(); cause(t, err) != UnknownMember {
		t.Fatalf("case variant admitted: %v", err)
	}
}

func TestEncodeIsCompactUnescapedAndTerminated(t *testing.T) {
	encoded, err := Encode(struct {
		B string `json:"b"`
		A []int  `json:"a"`
	}{"<&>", []int{}})
	if err != nil {
		t.Fatal(err)
	}
	if want := `{"b":"<&>","a":[]}` + "\n"; string(encoded) != want {
		t.Fatalf("got %q, want %q", encoded, want)
	}
}
