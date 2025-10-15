package com.wikiapp

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.edit

class LoadActivity : AppCompatActivity() {
    private lateinit var sharedPrefs: SharedPreferences
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_load)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        sharedPrefs = getSharedPreferences("state", MODE_PRIVATE)
        window.decorView.post {
            if (!sharedPrefs.getBoolean("prepFinished", false)) {
                Assets.makeDirs(this)
                Assets.copyAssets(this)
                Assets.extractMW(this)
                sharedPrefs.edit { putBoolean("prepFinished", true) }
            }
            val storageDir = sharedPrefs.getString("storage_dir", null)
            val targetDir = sharedPrefs.getString("target_dir", null)
            Assets.prepConfigs(this, storageDir, targetDir)
            if (Utils.targetHasFiles(this, storageDir, targetDir) == "ok") {
                Assets.runPhp(this)
                Assets.runNginx(this)
                startActivity(Intent(this, BrowserActivity::class.java))
            }
            else {
                startActivity(Intent(this, ConfigActivity::class.java))
            }
            finish()
        }
    }
}