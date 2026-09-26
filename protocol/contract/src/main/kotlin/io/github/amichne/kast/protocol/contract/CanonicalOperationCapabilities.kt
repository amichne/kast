package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.CapabilityMarker

interface IndexSyncCapability : CapabilityMarker

interface TopologyBuildCapability : CapabilityMarker

interface SourceReadCapability : CapabilityMarker

interface QueryRunCapability : CapabilityMarker

interface DiagnosticCheckCapability : CapabilityMarker

interface ChangePlanCapability : CapabilityMarker

interface ChangeCapability : CapabilityMarker

interface ChangeApplyCapability : CapabilityMarker

interface ChangeRecoverCapability : CapabilityMarker
