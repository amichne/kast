"""Closed disposable-fixture policies; production defaults and user profiles stay external."""
from dataclasses import dataclass
from enum import Enum


class NativeReadPolicy(str, Enum):
    DEFAULT = 'default'
    ENLARGED = 'enlarged'
    OVERFLOW = 'overflow'
    DIAGNOSTIC_PAGES = 'diagnostic-pages'


@dataclass(frozen=True)
class FixtureReadLimit:
    environmentKey: str
    value: int


@dataclass(frozen=True)
class FixtureReadPolicyReceipt:
    policy: NativeReadPolicy
    overrides: tuple[FixtureReadLimit, ...]
    event: str = 'kast_native_fixture_read_policy'


def policy_receipt(policy: NativeReadPolicy) -> FixtureReadPolicyReceipt:
    match policy:
        case NativeReadPolicy.DEFAULT:
            limits = ()
        case NativeReadPolicy.ENLARGED:
            limits = (
                FixtureReadLimit('KAST_READ_HOST_QUERY_MILLIS', 30000),
                FixtureReadLimit('KAST_READ_HOST_CONNECTION_MILLIS', 31000),
                FixtureReadLimit('KAST_READ_CLIENT_EXCHANGE_MILLIS', 32000),
            )
        case NativeReadPolicy.OVERFLOW:
            limits = (FixtureReadLimit('KAST_READ_EPOCH_VFS_EVENTS', 2),)
        case NativeReadPolicy.DIAGNOSTIC_PAGES:
            limits = (FixtureReadLimit('KAST_READ_DIAGNOSTIC_SCOPE_FILES', 2),)
        case _:
            raise TypeError('NativeReadPolicy required')
    return FixtureReadPolicyReceipt(policy, limits)
