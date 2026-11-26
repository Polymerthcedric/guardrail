package dev.fidelco.guardrail

internal fun shouldGatePackage(
    packageName: String,
    restrictedApps: Set<String>,
    lockdownActive: Boolean,
    recoveryPackages: Set<String> = emptySet(),
): Boolean {
    if (packageName in recoveryPackages) return false
    return lockdownActive || packageName in restrictedApps
}

internal fun packagesToObserve(
    restrictedApps: Set<String>,
    recoveryPackages: Set<String>,
    browserPackages: Set<String>,
): Set<String> = restrictedApps + recoveryPackages + browserPackages
