package io.carreader.app.screenshots

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.carreader.app.logging.AppAuditLog
import io.carreader.app.logging.AppInfo
import io.carreader.app.session.PreviewVehicleSession
import io.carreader.app.settings.InMemorySettings
import io.carreader.app.transport.BluetoothStatus
import io.carreader.app.transport.DeviceDiscovery
import io.carreader.app.transport.DeviceEntry
import io.carreader.app.transport.DeviceKind
import io.carreader.app.transport.TransportProvider
import io.carreader.app.ui.MainViewModel
import io.carreader.app.ui.confirm.DialogConfirmationPort
import io.carreader.app.ui.theme.CarReaderTheme
import io.carreader.core.guard.ClearOnlyPolicy
import io.carreader.core.log.RingTrafficLog
import io.carreader.core.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * Builds a real [MainViewModel] on top of [PreviewVehicleSession] (the in-app VW Passat with canned data), driven
 * by a virtual-time test dispatcher, so screenshots show the state the app really produces.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenshotHarness {
    val scheduler = TestCoroutineScheduler()
    val dispatcher = StandardTestDispatcher(scheduler)
    val settings = InMemorySettings()
    val confirmation = DialogConfirmationPort()
    val audit = AppAuditLog(file = null)
    val traffic = RingTrafficLog(clock = { FIXED_TIME })
    val scope = CoroutineScope(dispatcher)

    private class FakeTransport : Transport {
        override val description = "OBDLink MX+ · Bluetooth"
        override suspend fun connect() = Unit
        override suspend fun write(data: ByteArray) = Unit
        override suspend fun read(timeoutMs: Long) = ByteArray(0)
        override suspend fun close() = Unit
    }

    private val discovery = object : DeviceDiscovery {
        override fun status() = BluetoothStatus.READY
        override fun pairedDevices() = listOf(
            DeviceEntry("OBDLink MX+ 29467", "00:04:3E:9A:12:7C", DeviceKind.CLASSIC),
            DeviceEntry("JBL Tune 510BT", "F8:5C:7D:11:22:33", DeviceKind.CLASSIC),
        )
        override fun scanLe() = emptyFlow<DeviceEntry>()
    }

    val viewModel: MainViewModel = MainViewModel(
        transports = TransportProvider { FakeTransport() },
        sessionFactory = {
            PreviewVehicleSession(
                transportDescription = it.description,
                policy = ClearOnlyPolicy { settings.mutationsEnabled },
                confirmation = confirmation,
                audit = audit,
                clock = { FIXED_TIME },
            )
        },
        settings = settings,
        confirmation = confirmation,
        discovery = discovery,
        traffic = traffic,
        audit = audit,
        appInfo = AppInfo("0.1.0", 1, "Android 14", "screenshots"),
        cleanupScope = scope,
        io = dispatcher,
        clock = { FIXED_TIME },
    )

    companion object {
        /** 2026-03-14 09:41:00 local time, so logs and audit lines are stable. */
        const val FIXED_TIME = 1_773_481_260_000L
    }
}

/** Provides what an Activity would (back dispatcher, result registry) and applies the app theme. */
@Composable
fun ScreenshotHost(content: @Composable () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val backOwner = remember(lifecycleOwner) {
        object : OnBackPressedDispatcherOwner {
            override val onBackPressedDispatcher = OnBackPressedDispatcher()
            override val lifecycle: Lifecycle get() = lifecycleOwner.lifecycle
        }
    }
    val registryOwner = remember {
        object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) = Unit
            }
        }
    }
    CompositionLocalProvider(
        LocalOnBackPressedDispatcherOwner provides backOwner,
        LocalActivityResultRegistryOwner provides registryOwner,
    ) {
        CarReaderTheme(content = content)
    }
}
