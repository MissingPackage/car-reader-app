package io.carreader.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.carreader.app.ui.AppRoot
import io.carreader.app.ui.MainViewModel
import io.carreader.app.ui.theme.CarReaderTheme
import io.carreader.core.session.ConnectionState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        viewModelFactory { initializer { (application as CarReaderApplication).container.mainViewModel() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        keepScreenOnWhileConnected()
        setContent {
            CarReaderTheme {
                AppRoot(viewModel = viewModel, onExit = { finish() })
            }
        }
    }

    private fun keepScreenOnWhileConnected() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state
                    .map { it.connection is ConnectionState.Connected || it.connection is ConnectionState.Connecting }
                    .distinctUntilChanged()
                    .collect { on ->
                        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForeground()
    }

    override fun onStop() {
        // No polling the car while nobody looks at the values (home button, screen off, another app on top).
        viewModel.onAppBackground()
        super.onStop()
    }

    override fun onDestroy() {
        // Leaving the app for good (back on the connect screen, or swiped away): release the adapter.
        if (isFinishing) viewModel.disconnect()
        super.onDestroy()
    }
}
