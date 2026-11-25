package dev.fidelco.guardrail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockGateTest {
    private val blockedApps = setOf("com.example.social")

    @Test
    fun lockdownGatesPackagesOutsideTheConfiguredBlocklist() {
        assertTrue(shouldGatePackage("com.example.notes", blockedApps, lockdownActive = true))
    }

    @Test
    fun normalModeOnlyGatesConfiguredPackages() {
        assertTrue(shouldGatePackage("com.example.social", blockedApps, lockdownActive = false))
        assertFalse(shouldGatePackage("com.example.notes", blockedApps, lockdownActive = false))
    }
}
