"""Fixed native traversal fixture requests; identities enter from admitted discovery and continuation boundaries."""
from dataclasses import dataclass, field
from enum import Enum


class TraversalPositionKind(str, Enum):
    START = 'start'
    RESUME = 'resume'


class TraversalStrategyKind(str, Enum):
    BREADTH_FIRST = 'breadth_first'


class TraversalRelation(str, Enum):
    CALLERS = 'callers'


@dataclass(frozen=True)
class TraversalStart:
    type: TraversalPositionKind = field(default=TraversalPositionKind.START, init=False)


@dataclass(frozen=True)
class TraversalResume:
    continuation: str
    type: TraversalPositionKind = field(default=TraversalPositionKind.RESUME, init=False)


@dataclass(frozen=True)
class BreadthFirstStrategy:
    type: TraversalStrategyKind = field(default=TraversalStrategyKind.BREADTH_FIRST, init=False)


@dataclass(frozen=True)
class NativeTraversalRequest:
    exactSelector: str
    position: TraversalStart | TraversalResume
    relation: TraversalRelation = field(default=TraversalRelation.CALLERS, init=False)
    maximumDepth: int = field(default=4, init=False)
    maximumResults: int = field(default=100, init=False)
    strategy: BreadthFirstStrategy = field(default_factory=BreadthFirstStrategy, init=False)
