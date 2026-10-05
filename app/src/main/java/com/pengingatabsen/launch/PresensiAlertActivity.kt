package com.pengingatabsen.launch

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pengingatabsen.alarm.AlarmScheduler
import com.pengingatabsen.ui.theme.PengingatTheme

/**
 * Layar penuh "Presensi sudah dibuka!" (anti-lupa), muncul di atas layar kunci tepat saat dosen
 * membuka presensi. Hanya mengarahkan ke halaman presensi; tombol presensi tetap ditekan pengguna.
 */
class PresensiAlertActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        val courseId = intent.getLongExtra(AlarmScheduler.EXTRA_COURSE_ID, 0L)
        val epochDay = intent.getLongExtra(AlarmScheduler.EXTRA_EPOCH_DAY, 0L)
        val courseName = intent.getStringExtra(EXTRA_COURSE_NAME).orEmpty()
        val url = intent.getStringExtra(EXTRA_URL) ?: TargetApps.SIADIN_PRESENSI_URL

        setContent {
            PengingatTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("⏰", fontSize = 64.sp)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Presensi sudah dibuka!",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        courseName,
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(40.dp))
                    Button(
                        onClick = {
                            startActivity(WebBrowserActivity.intent(this@PresensiAlertActivity, url, courseId, epochDay))
                            finish()
                        },
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                    ) {
                        Text("Presensi sekarang", style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { finish() }) { Text("Nanti") }
                }
            }
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        private const val EXTRA_COURSE_NAME = "course_name"
        private const val EXTRA_URL = "url"

        fun intent(context: Context, courseId: Long, epochDay: Long, courseName: String, url: String): Intent =
            Intent(context, PresensiAlertActivity::class.java).apply {
                data = Uri.parse("pengingatabsen://layar-penuh/$courseId/$epochDay")
                putExtra(AlarmScheduler.EXTRA_COURSE_ID, courseId)
                putExtra(AlarmScheduler.EXTRA_EPOCH_DAY, epochDay)
                putExtra(EXTRA_COURSE_NAME, courseName)
                putExtra(EXTRA_URL, url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            }
    }
}
