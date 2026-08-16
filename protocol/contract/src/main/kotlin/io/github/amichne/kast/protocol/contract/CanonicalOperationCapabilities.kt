package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.CapabilityMarker

interface WorkspaceInspectCapability : CapabilityMarker

interface SymbolDiscoverCapability : CapabilityMarker

interface SymbolResolveCapability : CapabilityMarker

interface SymbolDescribeCapability : CapabilityMarker

interface RelationReadCapability : CapabilityMarker

interface TraversalRunCapability : CapabilityMarker

interface DiagnosticCheckCapability : CapabilityMarker

interface ChangePlanCapability : CapabilityMarker

interface ChangeApplyCapability : CapabilityMarker

interface ChangeVerifyCapability : CapabilityMarker

interface ChangeRecoverCapability : CapabilityMarker
