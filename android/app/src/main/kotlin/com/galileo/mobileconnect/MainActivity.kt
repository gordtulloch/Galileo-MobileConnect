package com.galileo.mobileconnect

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.galileo.mobileconnect.ui.ConnectedRoot
import com.galileo.mobileconnect.ui.LocalApp
import com.galileo.mobileconnect.ui.PairingView
import com.galileo.mobileconnect.ui.WelcomeView
import com.galileo.mobileconnect.ui.theme.ThemeChoice
import com.galileo.mobileconnect.ui.theme.ThemeRoot

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This app is used in the dark, at the telescope: the screen stays on while it is in front (docs/android/SDD.md 2.8).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        model.launched(
            LaunchOptions(
                demoScenario = intent.getStringExtra("galileo.demo"),
                theme = ThemeChoice.of(intent.getStringExtra("galileo.theme")?.replaceFirstChar { it.uppercase() }),
                noAutoConnect = intent.getBooleanExtra("galileo.noautoconnect", false),
            ),
        )
        setContent {
            CompositionLocalProvider(LocalApp provides model) {
                ThemeRoot(model.theme) {
                    SystemBars(model.theme)
                    Root(model)
                }
            }
        }
    }

    @Composable
    private fun SystemBars(choice: ThemeChoice) {
        // Night hides the status and navigation bars, whose icons would be the only things on screen that aren't red.
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (choice == ThemeChoice.Night) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    @Composable
    private fun Root(model: AppViewModel) {
        when (model.route) {
            Route.Welcome -> WelcomeView()
            Route.Pairing -> PairingView()
            Route.Connected -> ConnectedRoot()
        }
    }

    override fun onStart() {
        super.onStart()
        model.enteredForeground()
    }

    override fun onStop() {
        super.onStop()
        model.enteredBackground()
    }
}
