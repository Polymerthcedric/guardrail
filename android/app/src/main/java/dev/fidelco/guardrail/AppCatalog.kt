package dev.fidelco.guardrail

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

internal data class LaunchableApp(
    val packageName: String,
    val label: String
)

internal object AppCatalog {

    fun launchableApps(context: Context): List<LaunchableApp> {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val protectedPackages = protectedPackages(context)

        return queryLauncherActivities(packageManager, launcherIntent)
            .mapNotNull { resolveInfo ->
                val packageName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                val label = resolveInfo.loadLabel(packageManager).toString().trim()
                if (packageName in protectedPackages || label.isEmpty()) null
                else LaunchableApp(packageName, label)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun protectedPackages(context: Context): Set<String> =
        settingsPackages(context) + homePackages(context) + dialerPackages(context) + context.packageName

    fun settingsPackages(context: Context): Set<String> =
        setOfNotNull(resolvePackage(context, Intent(Settings.ACTION_SETTINGS)))

    private fun homePackages(context: Context): Set<String> =
        setOfNotNull(resolvePackage(context, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)))

    private fun dialerPackages(context: Context): Set<String> =
        setOfNotNull(resolvePackage(context, Intent(Intent.ACTION_DIAL)))

    @Suppress("DEPRECATION")
    private fun queryLauncherActivities(
        packageManager: PackageManager,
        intent: Intent
    ) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        packageManager.queryIntentActivities(intent, 0)
    }

    @Suppress("DEPRECATION")
    private fun resolvePackage(context: Context, intent: Intent): String? {
        val packageManager = context.packageManager
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return resolved?.activityInfo?.packageName
    }
}
