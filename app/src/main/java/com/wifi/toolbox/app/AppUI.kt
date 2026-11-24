package com.wifi.toolbox.app

import android.content.Context
import com.wifi.toolbox.R
import com.wifi.toolbox.ToolboxApp.AlertDialogData
import com.wifi.toolbox.ToolboxApp.SnackbarData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class AppUI(private val context: Context,
            private val scope: CoroutineScope) {

    private val _alertFlow = MutableSharedFlow<AlertDialogData?>(replay = 1)
    val alertFlow = _alertFlow.asSharedFlow()

    private val _snackFlow = MutableSharedFlow<SnackbarData>()
    val snackFlow = _snackFlow.asSharedFlow()

    /**
     * Shows a dialog
     * @param t Title
     * @param s Content
     * @return none
     */
    fun alert(t: String, s: String) {
        scope.launch {
            _alertFlow.emit(AlertDialogData(t, s))
        }
    }

    /**
     * Dismisses the dialog
     * @param none
     * @return none
     */
    fun dismissAlert() {
        scope.launch {
            _alertFlow.emit(null)
        }
    }

    /**
     * Shows a snackbar
     * @param m Message
     * @param l Button label
     * @param call Click callback
     * @return none
     */
    fun snackbar(m: String, l: String? = context.getString(R.string.btn_disappear), call: (() -> Unit)? = null) {
        scope.launch {
            _snackFlow.emit(SnackbarData(m, l, call))
        }
    }
}