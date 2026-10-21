package com.example.autofishai

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 32, 28, 32)
            setBackgroundColor(0xFF203A55.toInt())
        }
        fun label(s: String, size: Float = 18f) = TextView(this).apply {
            text = s; textSize = size; setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 10, 0, 10)
        }
        fun button(s: String, action: () -> Unit) = Button(this).apply {
            text = s; setOnClickListener { action() }
        }
        root.addView(label("🎣  AUTOFISH AI", 28f))
        root.addView(label("Prototype • OCR-assisted screen detection", 14f))
        root.addView(label("This prototype can look for visible Collect/Claim text. It cannot reliably understand every fishing minigame yet."))
        root.addView(button("1. Enable screen assistant") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(button("2. START AI") {
            getSharedPreferences("auto", MODE_PRIVATE).edit().putBoolean("running", true).apply()
            FishingAccessibilityService.running = true
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(button("STOP") {
            getSharedPreferences("auto", MODE_PRIVATE).edit().putBoolean("running", false).apply()
            FishingAccessibilityService.running = false
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(label("Android requires you to grant Accessibility permission manually. Keep this app open in the background while playing."))
        setContentView(root)
    }
}
