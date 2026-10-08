package org.olcbox.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.olcbox.app.data.datasource.LocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.exporter.AndroidLogExporter
import org.olcbox.app.data.identity.PersistentDeviceIdentityProvider
import org.olcbox.app.data.importer.AndroidConfigImporter
import org.olcbox.app.migration.LegacyMigration
import org.olcbox.app.ui.activities.AndroidMainScreen
import org.olcbox.app.ui.features.home.HomeScreenViewModel
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.olcbox.app.ui.theme.AppTheme
import org.olcbox.app.update.AppUpdateService
import org.olcbox.app.update.InstalledApkFingerprint
import org.olcbox.app.vpn.AndroidVpnManager

class AppActivity : ComponentActivity() {

    private lateinit var vpnManager: AndroidVpnManager
    private lateinit var viewModel: HomeScreenViewModel
    private lateinit var locationViewModel: LocationViewModel

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Permission handled
    }

    @Volatile
    private var lastBatteryPromptAtMs = 0L

    /**
     * Asks to be exempted from Doze/battery optimization so the VPN keeps running, and KEEPS asking on
     * every launch/resume until it is granted (like qWDTT) — a one-time prompt left users on
     * "Automatic", after which Doze killed the tunnel ~100s after screen-off. A short cooldown avoids a
     * dialog loop when the user declines.
     */
    private fun maybePromptBatteryOptimization() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val now = System.currentTimeMillis()
        if (now - lastBatteryPromptAtMs < 30_000L) return
        lastBatteryPromptAtMs = now
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            // Some OEMs block the direct request; fall back to the general exemption list.
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // One-time nudge to exclude the app from Android's battery optimization — the usual reason a
        // VPN foreground service gets throttled/killed over long idle sessions even while other apps
        // survive. Shown once on first launch; the Settings row can be used any time later.
        maybePromptBatteryOptimization()

        vpnManager = AndroidVpnManager(this)
        val locationsDataSource = LocationsDataSourceImpl(this)
        val locationsRepository = LocationsRepositoryImpl(locationsDataSource)
        val configImporter = AndroidConfigImporter(this)
        val logExporter = AndroidLogExporter(this)
        val updateService = AppUpdateService(
            deviceIdentityProvider = PersistentDeviceIdentityProvider(locationsDataSource),
            // Lets the updater pick the delta patch built against THIS exact APK (see
            // InstalledApkFingerprint) instead of guessing by ABI and downloading one that can't apply.
            installedFingerprint = { InstalledApkFingerprint.of(applicationContext) }
        )

        viewModel = HomeScreenViewModel(
            vpnManager = vpnManager,
            locationsRepository = locationsRepository,
            configImporter = configImporter,
            logExporter = logExporter
        )
        locationViewModel = LocationViewModel(
            locationsRepository = locationsRepository
        )

        enableEdgeToEdge()
        setContent {
            val dynamicThemeEnabled by vpnManager.dynamicThemeEnabled.collectAsState()

            AppTheme(useDynamicColor = dynamicThemeEnabled) {
                AndroidMainScreen(
                    viewModel = viewModel,
                    locationViewModel = locationViewModel,
                    vpnManager = vpnManager,
                    appUpdateService = updateService
                )
            }
        }

        handleDeepLink(intent)
        maybeWarnUnofficialBuild()
    }

    /**
     * One-time warning when the running build is NOT signed with the official YPtun release key —
     * i.e. someone repackaged and re-signed the app (ad/spyware injection, "стырили сборку"). This is
     * tamper-evident only: a repacker can patch this out. Shown once per install; acknowledged flag
     * persisted. Official builds short-circuit immediately.
     */
    private fun maybeWarnUnofficialBuild() {
        if (org.olcbox.app.security.IntegrityGuard.isOfficialInstalled(this)) return
        val prefs = getSharedPreferences("yourvpn_integrity", MODE_PRIVATE)
        if (prefs.getBoolean("unofficial_ack", false)) return
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("⚠ Неофициальная сборка / Unofficial build")
            .setMessage(
                "Эта копия your_vpn подписана не ключом разработчика — она могла быть изменена " +
                    "третьими лицами (реклама, слежка, вредонос). Скачайте оригинал:\n" +
                    "github.com/yanisplugg/olcvpn-client\n\n" +
                    "This your_vpn build is NOT signed by the developer and may have been modified by a " +
                    "third party. Get the official app from:\n" +
                    "github.com/yanisplugg/olcvpn-client"
            )
            .setCancelable(false)
            .setPositiveButton("OK") { dialog, _ ->
                prefs.edit().putBoolean("unofficial_ack", true).apply()
                dialog.dismiss()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Если настройки только что переехали со старой сборки - предложить её удалить (один раз).
        LegacyMigration.promptLegacyUninstallOnce(this)
        // Reinforce the battery exemption until granted (Doze otherwise kills the tunnel in idle).
        maybePromptBatteryOptimization()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /** Handles yptun:// deep links: import/{payload} and control/{start|stop|restart}. */
    private fun handleDeepLink(intent: Intent?) {
        val uri: Uri = intent?.data ?: return
        if (!uri.scheme.equals("yourvpn", ignoreCase = true)) return

        when (uri.host?.lowercase()) {
            // yptun://import deep-link removed (URL schemes disabled); internal control links remain.
            "control" -> {
                when (uri.lastPathSegment?.lowercase()) {
                    "start" -> if (!viewModel.state.value.isVpnConnected) viewModel.ToggleVpn()
                    "stop" -> if (viewModel.state.value.isVpnConnected) viewModel.ToggleVpn()
                    "restart" -> viewModel.restartVpnIfRunning()
                    // Widget "Auto" button: raise the signal the Home screen consumes to run the
                    // fastest-server search (needs the app foreground to ping every node + show progress).
                    "auto" -> org.olcbox.app.widget.WidgetAutoSignal.request()
                }
            }
        }
    }
}
