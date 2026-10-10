"""Pure admission of one observed native host; never launches a process."""
from dataclasses import dataclass
from enum import Enum
from pathlib import Path


class NativeHostFailure(str, Enum):
    UNAVAILABLE = 'EXACT_RUNNING_HOST_UNAVAILABLE'
    INVALID_PID = 'INVALID_NATIVE_HOST_PID'
    INVALID_OBSERVATION = 'INVALID_NATIVE_HOST_OBSERVATION'


@dataclass(frozen=True)
class NativeHostProcess:
    pid: int

    def __post_init__(self):
        if type(self.pid) is not int or self.pid <= 0:
            raise ValueError(NativeHostFailure.INVALID_PID.value)


@dataclass(frozen=True)
class RejectedNativeHost:
    failure: NativeHostFailure


@dataclass(frozen=True)
class NativeHostLifetime:
    process: NativeHostProcess
    started_at: str

    def __post_init__(self):
        if not isinstance(self.started_at, str) or not self.started_at:
            raise ValueError(NativeHostFailure.INVALID_OBSERVATION.value)


def select_native_host(rows: str, launcher: Path, requested_pid=None) -> NativeHostProcess | RejectedNativeHost:
    if requested_pid is not None and (type(requested_pid) is not int or requested_pid <= 0):
        return RejectedNativeHost(NativeHostFailure.INVALID_PID)
    observed = []
    for row in rows.splitlines():
        if not row.strip():
            continue
        fields = row.split(maxsplit=1)
        if len(fields) != 2 or not fields[0].isascii() or not fields[0].isdigit() or int(fields[0]) <= 0:
            return RejectedNativeHost(NativeHostFailure.INVALID_OBSERVATION)
        if fields[1] == str(launcher):
            observed.append(int(fields[0]))
    if len(observed) != len(set(observed)):
        return RejectedNativeHost(NativeHostFailure.INVALID_OBSERVATION)
    eligible = observed if requested_pid is None else [pid for pid in observed if pid == requested_pid]
    if len(eligible) != 1:
        return RejectedNativeHost(NativeHostFailure.UNAVAILABLE)
    return NativeHostProcess(eligible[0])


def admit_same_host(expected, current) -> NativeHostLifetime | RejectedNativeHost:
    """A replay cannot switch processes, including reuse of the same numeric PID."""
    for host in (expected, current):
        if (not isinstance(host, dict) or type(host.get('pid')) is not int or host['pid'] <= 0
                or not isinstance(host.get('processStart'), str) or not host['processStart']):
            return RejectedNativeHost(NativeHostFailure.INVALID_OBSERVATION)
    if (expected['pid'], expected['processStart']) != (current['pid'], current['processStart']):
        return RejectedNativeHost(NativeHostFailure.UNAVAILABLE)
    return NativeHostLifetime(NativeHostProcess(current['pid']), current['processStart'])
