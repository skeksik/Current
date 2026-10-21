package com.skeksik.current

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.skeksik.current.core.network.AndroidLocalNetworkAccess
import com.skeksik.current.core.network.PermissionAnswer
import com.skeksik.current.core.network.localNetworkRequests

class MainActivity : ComponentActivity() {
    // Registered with the activity, so a result that arrives after a rotation reaches the new one (ADR 0020).
    private val localNetworkLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val canAskAgain = if (granted) null else shouldShowRequestPermissionRationale(AndroidLocalNetworkAccess.PERMISSION)
        localNetworkRequests.onResult(PermissionAnswer(granted, canAskAgain))
    }
    private val launchLocalNetworkRequest = { localNetworkLauncher.launch(AndroidLocalNetworkAccess.PERMISSION) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        localNetworkRequests.attach(launchLocalNetworkRequest)

        setContent {
            App()
        }
    }

    override fun onDestroy() {
        localNetworkRequests.detach(launchLocalNetworkRequest, finishing = !isChangingConfigurations)
        super.onDestroy()
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}