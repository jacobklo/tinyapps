package net.jacoblo.moodlauncher

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import net.jacoblo.moodlauncher.ui.theme.MoodLauncherTheme

data class AppInfo(
    val label: String,
    val packageName: String,
    val icon: Drawable,
    val userHandle: UserHandle,
    val isSecondaryProfile: Boolean
)

class AppLauncherActivity : ComponentActivity() {

    private lateinit var launcherApps: LauncherApps

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        val apps = loadApps()

        setContent {
            MoodLauncherTheme {
                AppLauncherScreen(
                    apps = apps,
                    onAppClick = { launchApp(it) }
                )
            }
        }
    }

    private fun loadApps(): List<AppInfo> {
        val userManager = getSystemService(Context.USER_SERVICE) as UserManager
        val personalUser = Process.myUserHandle()
        val apps = mutableListOf<AppInfo>()

        for (profile in userManager.userProfiles) {
            val isSecondary = profile != personalUser
            val activities: List<LauncherActivityInfo> = try {
                launcherApps.getActivityList(null, profile) ?: continue
            } catch (_: Exception) {
                continue
            }
            for (activity in activities) {
                val icon = try {
                    activity.getIcon(resources.displayMetrics.densityDpi) ?: continue
                } catch (_: Exception) {
                    continue
                }
                apps += AppInfo(
                    label = activity.label?.toString()
                        ?: activity.applicationInfo.packageName,
                    packageName = activity.applicationInfo.packageName,
                    icon = icon,
                    userHandle = profile,
                    isSecondaryProfile = isSecondary
                )
            }
        }

        return apps.sortedBy { it.label.lowercase() }
    }

    private fun launchApp(app: AppInfo) {
        try {
            launcherApps
                .getActivityList(app.packageName, app.userHandle)
                ?.firstOrNull()
                ?.let { activity ->
                    launcherApps.startMainActivity(
                        activity.componentName,
                        app.userHandle,
                        null,
                        null
                    )
                }
        } catch (_: Exception) {
            // App may have been uninstalled or profile locked
        }
    }
}

@Composable
fun AppLauncherScreen(apps: List<AppInfo>, onAppClick: (AppInfo) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(72.dp),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        items(apps, key = { "${it.packageName}_${it.userHandle}" }) { app ->
            AppGridItem(app = app, onClick = { onAppClick(app) })
        }
    }
}

@Composable
fun AppGridItem(app: AppInfo, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp)
    ) {
        val bitmap = remember(app.packageName, app.userHandle) {
            runCatching { app.icon.toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }

        Box(modifier = Modifier.size(52.dp)) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = app.label,
                    modifier = Modifier.fillMaxSize()
                )
            }
            // Badge dot for work / private-space profile apps
            if (app.isSecondaryProfile) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .align(Alignment.BottomEnd)
                        .background(Color(0xFF1565C0), CircleShape)
                        .padding(2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "W",
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        color = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = app.label,
            fontSize = 10.sp,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            color = Color.Black,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 13.sp,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
