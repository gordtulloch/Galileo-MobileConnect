package com.galileo.mobileconnect.quest

import android.app.NativeActivity
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.galileo.mobileconnect.AppViewModel
import com.galileo.mobileconnect.LaunchOptions
import com.galileo.mobileconnect.ui.theme.ThemeChoice

/**
 * The Quest app's one activity. quest.cpp runs the OpenXR loop on its own thread; this class makes the app model and starts
 * [QuestHost], the Kotlin side that decides everything (docs/quest/SDD.md Q-HOST).
 *
 * For testing without pairing: `adb shell am start -n <package>/.QuestActivity --es galileo.demo normal-night` runs against the
 * simulated observatory, exactly as the tablet app does.
 */
class QuestActivity : NativeActivity() {
    private val store = ViewModelStore()
    private lateinit var model: AppViewModel
    private var host: QuestHost? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState) // loads libgalileo_quest and starts android_main
        model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[AppViewModel::class.java]
        model.launched(
            LaunchOptions(
                demoScenario = intent.getStringExtra("galileo.demo"),
                theme = ThemeChoice.of(intent.getStringExtra("galileo.theme")?.replaceFirstChar { it.uppercase() }),
                noAutoConnect = intent.getBooleanExtra("galileo.noautoconnect", false),
            ),
        )
        host = QuestHost(this, model).also { it.start() }
    }

    override fun onStart() {
        super.onStart()
        if (::model.isInitialized) model.enteredForeground()
    }

    override fun onStop() {
        super.onStop()
        // Held motion stops the moment the app is not in front, as everywhere else (MAPP-DEV-030).
        if (::model.isInitialized) model.enteredBackground()
        host?.releaseEverything()
    }

    override fun onDestroy() {
        host?.stop()
        host = null
        store.clear()
        super.onDestroy()
    }
}
