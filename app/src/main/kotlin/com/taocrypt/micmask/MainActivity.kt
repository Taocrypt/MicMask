package com.taocrypt.micmask

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    private var vmRef: MainViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        L.init(applicationContext)
        handleIntent(intent)
        // 启动检查：开关关闭时也查，只为强制更新（Updater 内部只在强制时弹框）
        com.taocrypt.micmask.update.Updater.check(applicationContext, silent = true)
        setContent {
            val vm: MainViewModel = viewModel()
            vmRef = vm
            com.taocrypt.micmask.ui2.V2App(vm)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: android.content.Intent?) {
        if (i?.getBooleanExtra("open_update", false) == true) com.taocrypt.micmask.update.Updater.dialog.value = true
    }

    // 从系统授权页 / 无障碍设置返回时刷新状态
    override fun onResume() {
        super.onResume()
        vmRef?.refreshQqWatchState()
        vmRef?.refreshInjectState()
        vmRef?.refreshFloatingState()
        vmRef?.refreshLiveFloat()
    }
}
