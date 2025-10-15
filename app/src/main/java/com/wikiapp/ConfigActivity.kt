package com.wikiapp

import android.Manifest.permission
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class ConfigActivity : AppCompatActivity() {
    private lateinit var targetSpinner: Spinner
    private lateinit var targetContainer: LinearLayout
    private lateinit var dirContainer: LinearLayout
    private lateinit var openBtn: Button
    private lateinit var grabberBtn: Button
    private lateinit var selectDirBtn: Button
    private lateinit var storagePrmBtn: Button
    private lateinit var batteryPrmBtn: Button
    private lateinit var batteryCheckBtn: Button
    private lateinit var targetErrorDisplay: TextView
    private lateinit var selectDirDisplay: TextView
    private lateinit var storagePrmDisplay: TextView
    private lateinit var batteryPrmDisplay: TextView
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var sharedPrefs: SharedPreferences
    private var askedIgnoreOptimizations = false
    private var selectedDir: String? = null
    @SuppressLint("BatteryLife")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_config)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(Type.systemBars())
            val imeInsets = insets.getInsets(Type.ime())
            val bottomPadding = maxOf(systemBars.bottom, imeInsets.bottom)
            v.updatePadding(systemBars.left, systemBars.top, systemBars.right, bottomPadding)
            insets
        }
        targetSpinner = findViewById(R.id.targetSpinner)
        targetContainer = findViewById(R.id.targetContainer)
        dirContainer = findViewById(R.id.dirContainer)
        openBtn = findViewById(R.id.openBtn)
        grabberBtn = findViewById(R.id.grabberBtn)
        selectDirBtn = findViewById(R.id.selectDirBtn)
        storagePrmBtn = findViewById(R.id.storagePrmBtn)
        batteryPrmBtn = findViewById(R.id.batteryPrmBtn)
        batteryCheckBtn = findViewById(R.id.batteryCheckBtn)
        targetErrorDisplay = findViewById(R.id.targetErrorDisplay)
        selectDirDisplay = findViewById(R.id.selectDirDisplay)
        storagePrmDisplay = findViewById(R.id.storagePrmDisplay)
        batteryPrmDisplay = findViewById(R.id.batteryPrmDisplay)
        sharedPrefs = getSharedPreferences("state", MODE_PRIVATE)
        requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { updateUI() }
        val dirPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri: Uri? ->
            if (treeUri != null) {
                contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                selectedDir = Utils.treeUriToAbsolutePath(this, treeUri)
                updateStorageDir()
                updateUI()
            }
        }
        openBtn.setOnClickListener {
            startActivity(Intent(this, LoadActivity::class.java))
            finish()
        }
        grabberBtn.setOnClickListener {
            startActivity(Intent(this, GrabberActivity::class.java))
        }
        storagePrmBtn.setOnClickListener {
            requestPermissionLauncher.launch(arrayOf(permission.WRITE_EXTERNAL_STORAGE, permission.READ_EXTERNAL_STORAGE))
        }
        batteryPrmBtn.setOnClickListener {
            val batteryIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:${packageName}".toUri()
            }
            startActivity(batteryIntent)
            lifecycleScope.launch {
                delay(1000)
                askedIgnoreOptimizations = true
                updateUI()
            }
        }
        batteryCheckBtn.setOnClickListener { updateUI() }
        selectDirBtn.setOnClickListener {
            dirPickerLauncher.launch(null)
        }
        selectedDir = sharedPrefs.getString("storage_dir", null)
        spinnerInit(targetSpinner)
        updateUI()
    }
    override fun onResume() {
        super.onResume()
        updateUI()
    }
    private fun updateStorageDir() {
        if (Utils.isStorageValid(selectedDir)) sharedPrefs.edit { putString("storage_dir", selectedDir) }
    }
    private fun updateUI(skipSpinner: Boolean = false) {
        val tDir = sharedPrefs.getString("target_dir", null)
        val pathUsable = Utils.isStorageValid(selectedDir)
        val storagePrm = hasStoragePrm()
        val batteryPrm = hasBatteryPrm()
        val targetUsable = Utils.isTargetValid(selectedDir, tDir)
        val targetHasFiles = Utils.targetHasFiles(this, selectedDir, tDir)
        if (pathUsable && !skipSpinner) updateSpinner(targetSpinner)
        targetContainer.visibility = if (!pathUsable) View.GONE else View.VISIBLE
        dirContainer.visibility = if (!storagePrm) View.GONE else View.VISIBLE
        openBtn.isEnabled = storagePrm && pathUsable && targetUsable && targetHasFiles == "ok"
        storagePrmBtn.visibility = if (storagePrm) View.GONE else View.VISIBLE
        batteryPrmBtn.visibility = if (batteryPrm) View.GONE else View.VISIBLE
        batteryCheckBtn.visibility = if (batteryPrm || !askedIgnoreOptimizations) View.GONE else View.VISIBLE
        selectDirBtn.text = getString(if (selectedDir == null) R.string.select_directory else R.string.change_directory)
        targetErrorDisplay.text = targetHasFiles
        targetErrorDisplay.visibility = if (targetHasFiles == "ok" || targetSpinner.selectedItemPosition == 0) View.GONE else View.VISIBLE
        selectDirDisplay.text = if (pathUsable) selectedDir else getString(if (selectedDir == null) R.string.no_select else R.string.bad_select)
        selectDirDisplay.isEnabled = pathUsable
        storagePrmDisplay.isEnabled = storagePrm
        storagePrmDisplay.text = getString(if (storagePrm) R.string.granted else R.string.not_granted)
        batteryPrmDisplay.isEnabled = batteryPrm
        batteryPrmDisplay.text = getString(if (batteryPrm) R.string.disabled else R.string.not_disabled)
    }
    private fun hasStoragePrm(): Boolean {
        val storageCheckRead = ContextCompat.checkSelfPermission(this, permission.READ_EXTERNAL_STORAGE)
        val storageCheckWrite = ContextCompat.checkSelfPermission(this, permission.WRITE_EXTERNAL_STORAGE)
        return storageCheckRead == PackageManager.PERMISSION_GRANTED && storageCheckWrite == PackageManager.PERMISSION_GRANTED
    }
    private fun hasBatteryPrm(): Boolean {
        return (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    }
    private fun updateSpinner(spinner: Spinner) {
        val options = ArrayList<String>()
        val tlsd = topLevelSubDirs(File(selectedDir as String))
        options.add(getString(R.string.select_hint))
        options.addAll(tlsd)
        val adapter = object : ArrayAdapter<String>(
            this,
            R.layout.spinner_item,
            R.id.spinner_text,
            options
        ) {
            override fun isEnabled(position: Int): Boolean {
                return position != 0
            }
        }
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinner.adapter = adapter
        val tDirName = sharedPrefs.getString("target_dir", null)
        spinner.setSelection(if (tDirName != null && tlsd.indexOf(tDirName) != -1) tlsd.indexOf(tDirName) + 1 else 0)
    }
    private fun spinnerInit(spinner: Spinner) {
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedItem = parent?.getItemAtPosition(position).toString()
                if (position != 0) {
                    sharedPrefs.edit { putString("target_dir", selectedItem) }
                    updateUI(true)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    private fun topLevelSubDirs(dir: File): List<String> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.sortedBy { it.lowercase() }
            ?: emptyList()
    }
}