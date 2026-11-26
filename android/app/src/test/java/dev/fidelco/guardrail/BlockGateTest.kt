package dev.fidelco.guardrail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockGateTest {
    private val restrictedApps = setOf("com.example.social")

    @Test
    fun lockdownGatesPackagesOutsideTheConfiguredBlocklist() {
        assertTrue(shouldGatePackage("com.example.notes", restrictedApps, lockdownActive = true))
    }

    @Test
    fun lockdownLeavesRecoveryPackagesAvailable() {
        val recoveryPackages = setOf("com.example.home", "com.example.settings", "com.example.phone")

        assertFalse(
            shouldGatePackage(
                "com.example.settings",
                restrictedApps,
                lockdownActive = true,
                recoveryPackages = recoveryPackages
            )
        )
    }

    @Test
    fun normalModeOnlyGatesConfiguredPackages() {
        assertTrue(shouldGatePackage("com.example.social", restrictedApps, lockdownActive = false))
        assertFalse(shouldGatePackage("com.example.notes", restrictedApps, lockdownActive = false))
    }

    @Test
    fun accessibilityFilterIncludesUserSelectedAndRecoveryPackages() {
        assertEquals(
            setOf("com.example.social", "com.example.settings", "com.example.home", "com.android.chrome"),
            packagesToObserve(
                restrictedApps,
                recoveryPackages = setOf("com.example.settings", "com.example.home"),
                browserPackages = setOf("com.android.chrome")
            )
        )
    }
}
