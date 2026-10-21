package com.skeksik.current

import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.tooling.preview.Preview
import com.skeksik.current.core.network.AndroidLocalNetworkAccess
import com.skeksik.current.core.network.PermissionAnswer
import com.skeksik.current.core.network.localNetworkRequests

class MainActivity : ComponentActivity() {
    private val input = MainWindowInput()

    // Registered with the activity, so a result that arrives after a rotation reaches the new one (ADR 0020).
    private val localNetworkLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val canAskAgain = if (granted) null else shouldShowRequestPermissionRationale(AndroidLocalNetworkAccess.PERMISSION)
        localNetworkRequests.onResult(PermissionAnswer(granted, canAskAgain))
    }
    private val launchLocalNetworkRequest = { localNetworkLauncher.launch(AndroidLocalNetworkAccess.PERMISSION) }

    // The first Tab after a tap leaves touch mode and is consumed there, before dispatchKeyEvent; Android then focuses the first
    // element. The listener runs before that focus moves, so the element shows its ring.
    private val touchModeListener = ViewTreeObserver.OnTouchModeChangeListener { inTouchMode ->
        if (!inTouchMode) input.onLeftTouchMode()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        localNetworkRequests.attach(launchLocalNetworkRequest)
        window.decorView.viewTreeObserver.addOnTouchModeChangeListener(touchModeListener)

        setContent {
            App(input)
        }
    }

    // A grant or denial in the settings reaches roaming only this way (ADR 0019).
    override fun onResume() {
        super.onResume()
        localNetworkRequests.resumed()
    }

    override fun onDestroy() {
        window.decorView.viewTreeObserver.takeIf { it.isAlive }?.removeOnTouchModeChangeListener(touchModeListener)
        localNetworkRequests.detach(launchLocalNetworkRequest, finishing = !isChangingConfigurations)
        super.onDestroy()
    }

    // Every key, before Compose: the first key after a tap, with nothing focused, still tells the focus rings.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        input.onPreviewKeyEvent(ComposeKeyEvent(event))
        return super.dispatchKeyEvent(event)
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}