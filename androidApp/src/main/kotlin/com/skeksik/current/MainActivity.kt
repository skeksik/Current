package com.skeksik.current

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    private val input = MainWindowInput()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            App(input)
        }
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