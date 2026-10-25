package com.novcloud.echo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.novcloud.echo.data.ThemeMode
import com.novcloud.echo.ui.AppRoot
import com.novcloud.echo.ui.theme.EchoTheme
import com.novcloud.echo.ui.theme.SystemBars
import com.novcloud.echo.ui.components.echoAura
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val openPlayer = MutableStateFlow(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val container = (application as EchoApp).container
        handle(intent)
        setContent {
            val mode by container.prefs.theme.collectAsState(initial = ThemeMode.System)
            val dark = when (mode) { ThemeMode.Dark -> true; ThemeMode.Light -> false; else -> isSystemInDarkTheme() }
            val lightStatus = dark || SystemBars.lightStatusIcons
            val lightNav = dark || SystemBars.lightNavIcons
            LaunchedEffect(lightStatus, lightNav) {
                val scrim = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (lightStatus) SystemBarStyle.dark(scrim) else SystemBarStyle.light(scrim, scrim),
                    navigationBarStyle = if (lightNav) SystemBarStyle.dark(scrim) else SystemBarStyle.light(scrim, scrim),
                )
            }
            EchoTheme(dark) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    var granted by remember { mutableStateOf(hasAudioPermission()) }
                    if (granted) {
                        LaunchedEffect(Unit) {
                            container.library.refresh()
                            container.player.connect()
                        }
                        AppRoot(openPlayer)
                    } else {
                        PermissionGate { granted = hasAudioPermission() }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_PLAYER) openPlayer.value = System.currentTimeMillis()
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        if (isFinishing) (application as EchoApp).container.player.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_OPEN_PLAYER = "com.novcloud.echo.OPEN_PLAYER"
        val audioPermission: String =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    }
}

@Composable
private fun PermissionGate(onResult: () -> Unit) {
    val perms = buildList {
        add(MainActivity.audioPermission)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val context = androidx.compose.ui.platform.LocalContext.current
    var asked by remember { mutableStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { asked++; onResult() }
    LaunchedEffect(Unit) { launcher.launch(perms) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        onResult()
        onPauseOrDispose { }
    }
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        com.novcloud.echo.ui.components.EchoMark(
            Modifier.size(112.dp).echoAura(true, com.novcloud.echo.ui.theme.Vermilion, corner = 12.dp, reach = 40.dp),
            color = MaterialTheme.colorScheme.onSurface, core = com.novcloud.echo.ui.theme.Vermilion,
        )
        Spacer(Modifier.height(36.dp))
        Text(androidx.compose.ui.res.stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(28.dp))
        com.novcloud.echo.ui.components.SolidButton("允许访问音乐") {
            if (asked >= 2) context.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null)),
            ) else launcher.launch(perms)
        }
    }
}

