package org.readerka

import android.os.Bundle
import android.content.Intent
import android.view.KeyEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import org.readerka.ui.LibraryViewModel
import org.readerka.ui.ReaderkaRoot

class MainActivity : ComponentActivity() {
    var workspaceShortcut: ((KeyEvent) -> Boolean)? = null
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.takeIf { it.scheme == "readerka" }?.let { ViewModelProvider(this)[LibraryViewModel::class.java].incomingLink(it.toString()) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { val vm: LibraryViewModel = viewModel(); ReaderkaRoot(this, vm) }
        intent.data?.takeIf { it.scheme == "readerka" }?.let { uri ->
            val vm = ViewModelProvider(this)[LibraryViewModel::class.java]
            vm.incomingLink(uri.toString())
        }
        val previous = window.callback
        window.callback = object : Window.Callback by previous {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean = workspaceShortcut?.invoke(event) == true || previous.dispatchKeyEvent(event)
        }
    }
}
