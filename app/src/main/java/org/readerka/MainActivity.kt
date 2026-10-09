package org.readerka

import android.os.Bundle
import android.view.KeyEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import org.readerka.ui.LibraryViewModel
import org.readerka.ui.ReaderkaRoot

class MainActivity : ComponentActivity() {
    var workspaceShortcut: ((KeyEvent) -> Boolean)? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { val vm: LibraryViewModel = viewModel(); ReaderkaRoot(this, vm) }
        val previous = window.callback
        window.callback = object : Window.Callback by previous {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean = workspaceShortcut?.invoke(event) == true || previous.dispatchKeyEvent(event)
        }
    }
}
