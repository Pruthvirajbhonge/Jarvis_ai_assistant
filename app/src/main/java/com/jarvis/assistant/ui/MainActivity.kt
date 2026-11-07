package com.jarvis.assistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CompoundButton
import android.widget.Toast
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.R
import com.jarvis.assistant.core.Connection
import com.jarvis.assistant.core.UiState
import com.jarvis.assistant.data.model.BrowserTab
import com.jarvis.assistant.data.prefs.JarvisSettings
import com.jarvis.assistant.databinding.ActivityMainBinding
import com.jarvis.assistant.databinding.DialogSettingsBinding
import com.jarvis.assistant.service.JarvisBackgroundService
import com.jarvis.assistant.speech.VoiceInteractionManager.Mode
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var deckSectionIndex = 0
    private val deckSections = listOf("QUICK ACTIONS", "SYSTEM COMMANDS", "MEDIA CONTROLS")

    private val activeTabsList = mutableListOf(
        BrowserTab("1", "Google", "www.google.com", R.drawable.ic_search),
        BrowserTab("2", "YouTube", "m.youtube.com", R.drawable.ic_youtube)
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            startJarvisBackgroundService()
            val callingDenied = results[Manifest.permission.CALL_PHONE] == false ||
                results[Manifest.permission.READ_CONTACTS] == false
            if (callingDenied) {
                Toast.makeText(
                    this,
                    "Allow Phone and Contacts permission, otherwise I can't call people by voice.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val pcSwitchListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        performHaptic()
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

        setupClickListeners()
        setupControlDeck()
        setupBottomNavigation()
        setupTabPreview()
        observeState()
        requestMandatoryPermissions()
    }

    override fun onResume() {
        super.onResume()
        startJarvisBackgroundService()
    }

    // ---- Click & Navigation Listeners --------------------------------------------------------

    private fun setupClickListeners() {
        binding.btnMicToggle.setOnClickListener { performHaptic(); listen() }
        binding.reactorView.setOnClickListener { performHaptic(); listen() }
        binding.btnSend.setOnClickListener { performHaptic(); sendTyped() }
        binding.etCommandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
        }
        binding.btnSettings.setOnClickListener { performHaptic(); showSettings() }
        binding.btnNotification.setOnClickListener { performHaptic(); showHistoryDialog() }
    }

    private fun setupControlDeck() {
        binding.btnPrevSection.setOnClickListener {
            performHaptic()
            deckSectionIndex = if (deckSectionIndex > 0) deckSectionIndex - 1 else deckSections.size - 1
            updateControlDeckUI()
        }

        binding.btnNextSection.setOnClickListener {
            performHaptic()
            deckSectionIndex = (deckSectionIndex + 1) % deckSections.size
            updateControlDeckUI()
        }

        updateControlDeckUI()
    }

    private fun updateControlDeckUI() {
        binding.tvDeckSectionTitle.text = deckSections[deckSectionIndex]

        when (deckSectionIndex) {
            0 -> { // QUICK ACTIONS
                binding.btnDeckAction1.text = "Open Apps"
                binding.btnDeckAction1.setOnClickListener { performHaptic(); viewModel.submit("open apps") }

                binding.btnDeckAction2.text = "Search Web"
                binding.btnDeckAction2.setOnClickListener { performHaptic(); viewModel.submit("open chrome") }

                binding.btnDeckAction3.text = "Open YouTube"
                binding.btnDeckAction3.setOnClickListener { performHaptic(); viewModel.submit("open youtube") }

                binding.btnDeckAction4.text = "Play Music"
                binding.btnDeckAction4.setOnClickListener { performHaptic(); viewModel.submit("open spotify") }

                binding.btnDeckAction5.text = "PC Control"
                binding.btnDeckAction5.setOnClickListener { performHaptic(); viewModel.setPcMode(!binding.switchPcMode.isChecked) }

                binding.btnDeckAction6.text = "More Actions"
                binding.btnDeckAction6.setOnClickListener { performHaptic(); showTabManagerDialog() }
            }
            1 -> { // SYSTEM COMMANDS
                binding.btnDeckAction1.text = "Toggle Torch"
                binding.btnDeckAction1.setOnClickListener { performHaptic(); viewModel.submit("toggle torch") }

                binding.btnDeckAction2.text = "Volume Up"
                binding.btnDeckAction2.setOnClickListener { performHaptic(); viewModel.submit("volume up") }

                binding.btnDeckAction3.text = "Volume Down"
                binding.btnDeckAction3.setOnClickListener { performHaptic(); viewModel.submit("volume down") }

                binding.btnDeckAction4.text = "Mute Volume"
                binding.btnDeckAction4.setOnClickListener { performHaptic(); viewModel.submit("mute volume") }

                binding.btnDeckAction5.text = "Phone Mode"
                binding.btnDeckAction5.setOnClickListener { performHaptic(); viewModel.setPcMode(false) }

                binding.btnDeckAction6.text = "System Info"
                binding.btnDeckAction6.setOnClickListener { performHaptic(); viewModel.submit("battery status") }
            }
            2 -> { // MEDIA CONTROLS
                binding.btnDeckAction1.text = "Play Music"
                binding.btnDeckAction1.setOnClickListener { performHaptic(); viewModel.submit("open spotify") }

                binding.btnDeckAction2.text = "Volume 80%"
                binding.btnDeckAction2.setOnClickListener { performHaptic(); viewModel.submit("set volume 80 percent") }

                binding.btnDeckAction3.text = "Take Screenshot"
                binding.btnDeckAction3.setOnClickListener { performHaptic(); viewModel.submit("take screenshot") }

                binding.btnDeckAction4.text = "Media History"
                binding.btnDeckAction4.setOnClickListener { performHaptic(); showHistoryDialog() }

                binding.btnDeckAction5.text = "Pause Media"
                binding.btnDeckAction5.setOnClickListener { performHaptic(); viewModel.submit("pause music") }

                binding.btnDeckAction6.text = "Next Track"
                binding.btnDeckAction6.setOnClickListener { performHaptic(); viewModel.submit("next track") }
            }
        }
    }

    private fun setupBottomNavigation() {
        binding.navHome.setOnClickListener {
            performHaptic()
            updateNavSelection(0)
            binding.mainScrollView.smoothScrollTo(0, 0)
        }

        binding.navTabs.setOnClickListener {
            performHaptic()
            updateNavSelection(1)
            showTabManagerDialog()
        }

        binding.navCentralReactorBtn.setOnClickListener {
            performHaptic()
            listen()
        }

        binding.navHistory.setOnClickListener {
            performHaptic()
            updateNavSelection(3)
            showHistoryDialog()
        }

        binding.navSettings.setOnClickListener {
            performHaptic()
            updateNavSelection(4)
            showSettings()
        }
    }

    private fun updateNavSelection(index: Int) {
        val selectedColor = Color.parseColor("#00E5FF")
        val unselectedColor = Color.parseColor("#90CAF9")

        binding.ivNavHome.setColorFilter(if (index == 0) selectedColor else unselectedColor)
        binding.tvNavHome.setTextColor(if (index == 0) selectedColor else unselectedColor)

        binding.ivNavTabs.setColorFilter(if (index == 1) selectedColor else unselectedColor)
        binding.tvNavTabs.setTextColor(if (index == 1) selectedColor else unselectedColor)

        binding.ivNavHistory.setColorFilter(if (index == 3) selectedColor else unselectedColor)
        binding.tvNavHistory.setTextColor(if (index == 3) selectedColor else unselectedColor)

        binding.ivNavSettings.setColorFilter(if (index == 4) selectedColor else unselectedColor)
        binding.tvNavSettings.setTextColor(if (index == 4) selectedColor else unselectedColor)
    }

    private fun setupTabPreview() {
        updateTabPreviewUI()

        binding.btnViewAllTabs.setOnClickListener {
            performHaptic()
            showTabManagerDialog()
        }

        binding.btnCloseTab1.setOnClickListener {
            performHaptic()
            if (activeTabsList.isNotEmpty()) activeTabsList.removeAt(0)
            updateTabPreviewUI()
        }

        binding.btnCloseTab2.setOnClickListener {
            performHaptic()
            if (activeTabsList.size > 1) activeTabsList.removeAt(1)
            else if (activeTabsList.isNotEmpty()) activeTabsList.removeAt(0)
            updateTabPreviewUI()
        }
    }

    private fun updateTabPreviewUI() {
        binding.tvActiveTabsCount.text = "ACTIVE TABS (${activeTabsList.size})"
        if (activeTabsList.isEmpty()) {
            binding.cardActiveTabs.visibility = View.GONE
        } else {
            binding.cardActiveTabs.visibility = View.VISIBLE
            binding.cardTab1.visibility = if (activeTabsList.size >= 1) View.VISIBLE else View.GONE
            binding.cardTab2.visibility = if (activeTabsList.size >= 2) View.VISIBLE else View.GONE
        }
    }

    private fun showTabManagerDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_tab_manager, null)
        val rvList = dialogView.findViewById<RecyclerView>(R.id.rvTabList)
        val btnCloseAll = dialogView.findViewById<View>(R.id.btnCloseAllTabs)
        val btnDone = dialogView.findViewById<View>(R.id.btnTabDialogClose)

        val adapter = TabAdapter(
            activeTabsList,
            onTabClick = { tab ->
                viewModel.submit("open ${tab.title}")
            },
            onTabClose = { tab ->
                activeTabsList.remove(tab)
                updateTabPreviewUI()
            }
        )

        rvList.layoutManager = LinearLayoutManager(this)
        rvList.adapter = adapter

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

        btnCloseAll.setOnClickListener {
            performHaptic()
            activeTabsList.clear()
            adapter.updateTabs(emptyList())
            updateTabPreviewUI()
        }

        btnDone.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showHistoryDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("J.A.R.V.I.S. Command History")
            .setMessage(binding.tvResponseText.text)
            .setPositiveButton("OK", null)
            .show()
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
        // Update Arc Reactor View state & audio RMS amplitude
        val reactorState = when {
            !s.note.isNullOrBlank() && (s.note.contains("fail", true) || s.note.contains("error", true)) -> JarvisReactorView.State.ERROR
            s.busy -> JarvisReactorView.State.PROCESSING
            s.mode == Mode.COMMAND -> JarvisReactorView.State.LISTENING
            else -> JarvisReactorView.State.IDLE
        }
        binding.reactorView.setReactorState(reactorState)
        binding.reactorView.setAudioRms(s.audioRms)

        // Reply text update
        val executed = s.lastAction?.takeIf { it.isNotBlank() && it != s.reply }
        binding.tvResponseText.text = if (executed != null) "${s.reply}\n\n⚙ $executed" else s.reply

        binding.tvAssistantStatus.text = when {
            s.busy -> s.note ?: "Processing command..."
            s.mode == Mode.COMMAND -> "Listening..."
            !s.note.isNullOrBlank() -> s.note
            s.mode == Mode.WAKE -> "Listening for \"Hey Jarvis\"..."
            else -> "Ready."
        }

        val (label, color) = when (s.connection) {
            Connection.PHONE_READY -> "PHONE READY" to "#00E676"
            Connection.NO_AI -> "NO AI KEY" to "#FFB300"
            Connection.PC_ONLINE -> "Connected\nPC Online" to "#00E5FF"
            Connection.PC_OFFLINE -> "PC Offline" to "#FF5252"
        }
        binding.tvConnectionStatus.text = label
        binding.tvConnectionStatus.setTextColor(Color.parseColor(color))

        // Update the switch without re-triggering listener
        binding.switchPcMode.setOnCheckedChangeListener(null)
        binding.switchPcMode.isChecked = s.pcMode
        binding.switchPcMode.setOnCheckedChangeListener(pcSwitchListener)

        val views = listOf(binding.tvCpuMetric, binding.tvRamMetric, binding.tvDiskMetric)
        s.stats.forEachIndexed { i, st ->
            views.getOrNull(i)?.text = if (st.value == null) "${st.label}: --" else "${st.value}%"
        }
    }

    // ---- Settings dialog --------------------------------------------------------------------

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
            b.etGeminiModel.hint = JarvisSettings.DEFAULT_GEMINI
            b.etGroqModel.hint = JarvisSettings.DEFAULT_GROQ
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

    private fun performHaptic() {
        binding.root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    // ---- Permissions & Service --------------------------------------------------------------

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

    private fun startJarvisBackgroundService() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) return
        ContextCompat.startForegroundService(this, Intent(this, JarvisBackgroundService::class.java))
    }
}
