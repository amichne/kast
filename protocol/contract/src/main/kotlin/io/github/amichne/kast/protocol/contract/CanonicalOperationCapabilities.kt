package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.CapabilityMarker

interface IndexSyncCapability : CapabilityMarker

interface TopologyBuildCapability : CapabilityMarker

interface SymbolDiscoverCapability : CapabilityMarker

interface SymbolInspectCapability : CapabilityMarker

interface SourceReadCapability : CapabilityMarker

interface RelationReadCapability : CapabilityMarker

interface TraversalRunCapability : CapabilityMarker

interface DiagnosticCheckCapability : CapabilityMarker

interface ChangePlanCapability : CapabilityMarker

interface ChangeApplyCapability : CapabilityMarker

interface ChangeRecoverCapability : CapabilityMarker
