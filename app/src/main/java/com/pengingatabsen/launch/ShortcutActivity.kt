package com.pengingatabsen.launch

import android.app.Activity
import android.os.Bundle

/**
 * Pintasan ikon (tekan lama ikon NgiBsen → "Buka presensi"): langsung membuka halaman presensi. Tanpa mencatat
 * apa pun (aman dipanggil dari luar), sama seperti membuka SiAdin sendiri.
 */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(LaunchTargetActivity.intent(this))
        finish()
    }
}
