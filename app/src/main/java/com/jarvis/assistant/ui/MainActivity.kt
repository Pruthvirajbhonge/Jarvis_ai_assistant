package com.jarvis.assistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.view.inputmethod.EditorInfo
import android.widget.CompoundButton
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.core.Connection
import com.jarvis.assistant.core.UiState
import com.jarvis.assistant.data.prefs.JarvisSettings
import com.jarvis.assistant.databinding.ActivityMainBinding
import com.jarvis.assistant.databinding.DialogSettingsBinding
import com.jarvis.assistant.service.JarvisBackgroundService
import com.jarvis.assistant.speech.VoiceInteractionManager.Mode
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            startJarvisBackgroundService()
        }

    private val pcSwitchListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        viewModel.setPcMode(checked)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setupAnimations()
        setupClickListeners()
        observeState()
        requestMandatoryPermissions()
    }

    override fun onResume() {
        super.onResume()
        startJarvisBackgroundService()
    }

    // ---- UI ---------------------------------------------------------------------------------

    private fun setupAnimations() {
        binding.ivReactorOuterRing.startAnimation(
            RotateAnimation(
                0f, 360f,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f
            ).apply {
                duration = 6000
                repeatCount = Animation.INFINITE
                interpolator = LinearInterpolator()
            }
        )
    }

    private fun setupClickListeners() {
        binding.btnMicToggle.setOnClickListener { listen() }
        binding.ivReactorCore.setOnClickListener { listen() }
        binding.btnSend.setOnClickListener { sendTyped() }
        binding.etCommandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
        }
        binding.btnSettings.setOnClickListener { showSettings() }
    }

    private fun listen() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            requestMandatoryPermissions()
            return
        }
        viewModel.listen()
    }

    private fun sendTyped() {
        val text = binding.etCommandInput.text.toString().trim()
        if (text.isNotEmpty()) {
            viewModel.submit(text)
            binding.etCommandInput.text?.clear()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(s: UiState) {
        binding.tvResponseText.text = s.reply
        binding.tvAssistantStatus.text = when {
            s.busy -> s.note ?: "Processing command..."
            s.mode == Mode.COMMAND -> "Listening..."
            !s.note.isNullOrBlank() -> s.note
            s.mode == Mode.WAKE -> "Listening in background... Say 'Hey Jarvis'"
            else -> "Ready."
        }

        val (label, color) = when (s.connection) {
            Connection.PHONE_READY -> "PHONE" to "#00E676"
            Connection.NO_AI -> "NO AI KEY" to "#FFB300"
            Connection.PC_ONLINE -> "PC • ONLINE" to "#00E5FF"
            Connection.PC_OFFLINE -> "PC • OFFLINE" to "#FF5252"
        }
        binding.tvConnectionStatus.text = label
        binding.tvConnectionStatus.setTextColor(Color.parseColor(color))

        // Update the switch without re-triggering its own listener.
        binding.switchPcMode.setOnCheckedChangeListener(null)
        binding.switchPcMode.isChecked = s.pcMode
        binding.switchPcMode.setOnCheckedChangeListener(pcSwitchListener)

        val views = listOf(binding.tvCpuMetric, binding.tvRamMetric, binding.tvDiskMetric)
        s.stats.forEachIndexed { i, st ->
            views.getOrNull(i)?.text = if (st.value == null) "${st.label}: --" else "${st.label}: ${st.value}%"
        }
    }

    // ---- settings dialog --------------------------------------------------------------------

    private fun readSettings(b: DialogSettingsBinding, base: JarvisSettings) = base.copy(
        geminiKey = b.etGeminiKey.text.toString(),
        geminiModel = b.etGeminiModel.text.toString(),
        groqKey = b.etGroqKey.text.toString(),
        groqModel = b.etGroqModel.text.toString(),
        xaiKey = b.etXaiKey.text.toString(),
        xaiModel = b.etXaiModel.text.toString(),
        openRouterKey = b.etOpenRouterKey.text.toString(),
        openRouterModel = b.etOpenRouterModel.text.toString(),
        pcHost = b.etPcHost.text.toString(),
        token = b.etToken.text.toString(),
        wakeWordEnabled = b.switchWake.isChecked,
        floatingOrb = b.switchOrb.isChecked
    )

    private fun showSettings() {
        lifecycleScope.launch {
            val current = viewModel.settings.current()
            val b = DialogSettingsBinding.inflate(layoutInflater)
            b.etGeminiKey.setText(current.geminiKey)
            b.etGeminiModel.setText(current.geminiModel)
            b.etGroqKey.setText(current.groqKey)
            b.etGroqModel.setText(current.groqModel)
            b.etXaiKey.setText(current.xaiKey)
            b.etXaiModel.setText(current.xaiModel)
            b.etOpenRouterKey.setText(current.openRouterKey)
            b.etOpenRouterModel.setText(current.openRouterModel)
            b.etPcHost.setText(current.pcHost)
            b.etToken.setText(current.token)
            b.switchWake.isChecked = current.wakeWordEnabled
            b.switchOrb.isChecked = current.floatingOrb

            b.btnTestAi.setOnClickListener {
                val typed = readSettings(b, current)
                b.btnTestAi.isEnabled = false
                b.btnTestAi.text = "Testing…"
                lifecycleScope.launch {
                    val report = viewModel.testAi(typed)
                    b.btnTestAi.isEnabled = true
                    b.btnTestAi.text = "Test AI connection"
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("AI connection test")
                        .setMessage(report)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            b.btnAccessibility.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            b.btnNotifAccess.setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            b.btnOverlay.setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            b.btnWriteSettings.setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            }
            b.btnBattery.setOnClickListener {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }

            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("J.A.R.V.I.S. Settings")
                .setView(b.root)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save") { _, _ ->
                    lifecycleScope.launch {
                        viewModel.settings.save(readSettings(b, current))
                        startJarvisBackgroundService()
                    }
                }
                .show()
        }
    }

    // ---- permissions & service --------------------------------------------------------------

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requestMandatoryPermissions() {
        val wanted = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = wanted.filterNot(::hasPermission)
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    /** Android 14+ refuses a microphone foreground service until RECORD_AUDIO is granted. */
    private fun startJarvisBackgroundService() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) return
        ContextCompat.startForegroundService(this, Intent(this, JarvisBackgroundService::class.java))
    }
}
