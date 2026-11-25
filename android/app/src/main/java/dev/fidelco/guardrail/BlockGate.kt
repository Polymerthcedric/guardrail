package dev.fidelco.guardrail

internal fun shouldGatePackage(
    packageName: String,
    blockedApps: Set<String>,
    lockdownActive: Boolean,
): Boolean = lockdownActive || packageName in blockedApps
