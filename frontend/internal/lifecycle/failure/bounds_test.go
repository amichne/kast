package failure_test

import (
	"fmt"
	"strings"
	"testing"

	"github.com/amichne/kast/frontend/internal/lifecycle/codec"
	"github.com/amichne/kast/frontend/internal/lifecycle/failure"
)

// TestFailureIntegersRejectOutsideTheirLimits decodes each integer at and just past each limit. A value outside is
// the typed INVALID_VALUE rejection, never a clamped or wrapped number; a value inside re-encodes unchanged.
func TestFailureIntegersRejectOutsideTheirLimits(t *testing.T) {
	const (
		path   = `"root":"R","path":"current"`
		wrap32 = int64(1) << 32
	)
	document := func(code, evidence string) func(int64) string {
		return func(value int64) string {
			return `{"schemaVersion":1,"code":"` + code + `","stage":"VERIFY","evidence":{` +
				strings.Replace(evidence, "#", fmt.Sprint(value), 1) + "}}\n"
		}
	}
	cases := []struct {
		name     string
		document func(int64) string
		admitted []int64
		rejected []int64
	}{
		{"lock holder", document("LOCK_HELD", `"range":"MAIN","mode":"SHARED","holder":#`),
			[]int64{0, 1, 1<<31 - 1}, []int64{-1, 1 << 31, wrap32, wrap32 + 1, -1 << 31, -1<<31 - 1}},
		{"process holder", document("PROCESS_STILL_ALIVE", `"holder":#,"use":"ARGUMENT"`),
			[]int64{1, 1<<31 - 1}, []int64{-1, 0, 1 << 31, wrap32 + 1}},
		{"exit code", document("RETIREMENT_REJECTED", `"exitCode":#,"reason":"LOGIN_AGENT_FOREIGN"`),
			[]int64{-1, 0, 255}, []int64{-2, 256, wrap32}},
		{"HTTP status", document("ASSET_UNAVAILABLE", `"asset":"a","cause":"HTTP_STATUS","httpStatus":#`),
			[]int64{100, 599}, []int64{-1, 0, 99, 600, wrap32 + 100}},
		{"status of another cause", document("ASSET_UNAVAILABLE", `"asset":"a","cause":"TIMEOUT","httpStatus":#`),
			[]int64{0}, []int64{-1, 1, 100, wrap32}},
		{"unmapped errno", document("FILESYSTEM_REJECTED", path+`,"operation":"OPEN","cause":"UNMAPPED_ERRNO","errno":#`),
			[]int64{1, failure.MaximumErrno}, []int64{-1, 0, failure.MaximumErrno + 1, wrap32 + 1}},
		{"errno of a mapped cause", document("FILESYSTEM_REJECTED", path+`,"operation":"OPEN","cause":"IO","errno":#`),
			[]int64{0}, []int64{-1, 1, wrap32}},
	}
	for _, c := range cases {
		for _, value := range c.admitted {
			raw := c.document(value)
			decoded, err := failure.DecodeFailure([]byte(raw))
			if err != nil || string(decoded.Encode()) != raw {
				t.Errorf("%s %d: %v", c.name, value, err)
			}
		}
		for _, value := range c.rejected {
			decoded, err := failure.DecodeFailure([]byte(c.document(value)))
			if cause, ok := codec.CauseOf(err); !ok || cause != codec.InvalidValue || decoded != (failure.Failure{}) {
				t.Errorf("%s %d: decoded %v, %v", c.name, value, decoded, err)
			}
		}
	}
}
