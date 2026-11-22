package io.carreader.app

import android.app.Application
import android.os.Build
import io.carreader.app.logging.AppAuditLog
import io.carreader.app.logging.AppInfo
import io.carreader.app.session.SessionDependencies
import io.carreader.app.session.SessionFactory
import io.carreader.app.settings.SettingsRepository
import io.carreader.app.settings.SharedPreferencesSettings
import io.carreader.app.transport.AndroidDeviceDiscovery
import io.carreader.app.transport.AndroidTransportProvider
import io.carreader.app.ui.MainViewModel
import io.carreader.app.ui.confirm.DialogConfirmationPort
import io.carreader.core.guard.ClearOnlyPolicy
import io.carreader.core.log.RingTrafficLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Process-wide singletons. */
class CarReaderApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(private val app: Application) {
    val clock: () -> Long = System::currentTimeMillis
    val settings: SettingsRepository = SharedPreferencesSettings(app)
    val traffic = RingTrafficLog(capacity = 20_000, clock = clock)
    val audit = AppAuditLog(File(app.filesDir, "audit.log"))
    val confirmation = DialogConfirmationPort()
    val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sessionDeps = SessionDependencies(
        policy = ClearOnlyPolicy { settings.mutationsEnabled },
        confirmation = confirmation,
        audit = audit,
        traffic = traffic,
        clock = clock,
    )

    val appInfo: AppInfo by lazy {
        AppInfo(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
    }

    fun mainViewModel(): MainViewModel = MainViewModel(
        transports = AndroidTransportProvider(app),
        sessionFactory = { transport -> SessionFactory.create(transport, sessionDeps) },
        settings = settings,
        confirmation = confirmation,
        discovery = AndroidDeviceDiscovery(app),
        traffic = traffic,
        audit = audit,
        appInfo = appInfo,
        cleanupScope = cleanupScope,
    )
}
