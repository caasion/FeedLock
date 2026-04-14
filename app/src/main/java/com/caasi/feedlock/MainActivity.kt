package com.caasi.feedlock

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var enableButton: Button
    private lateinit var usernameSection: View
    private lateinit var usernameInput: EditText
    private lateinit var saveButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        enableButton = findViewById(R.id.enableButton)
        usernameSection = findViewById(R.id.usernameSection)
        usernameInput = findViewById(R.id.usernameInput)
        saveButton = findViewById(R.id.saveButton)

        enableButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        if (isAccessibilityServiceEnabled()) {
            statusText.text = "FeedLock is active"
            enableButton.visibility = View.GONE
            usernameSection.visibility = View.VISIBLE
        } else {
            statusText.text = "Accessibility permission required"
            enableButton.visibility = View.VISIBLE
            usernameSection.visibility = View.GONE
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedService = packageName + "/com.caasi.feedlock.FeedLockService"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(expectedService)
    }
}