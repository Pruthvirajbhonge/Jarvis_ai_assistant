package com.jarvis.assistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.R
import com.jarvis.assistant.automation.JarvisAccessibilityService
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
        BrowserTab("1", "YouTube", "m.youtube.com", R.drawable.ic_youtube, true),
        BrowserTab("2", "Google", "www.google.com", R.drawable.ic_search, false)
    )

    private lateinit var tabAdapter: TabAdapter

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
        setupTabsScreen()
        setupSettingsScreen()
        observeState()
        requestMandatoryPermissions()
    }

    override fun onResume() {
        super.onResume()
        startJarvisBackgroundService()
        checkBackgroundSetup()
    }

    // ---- background setup (without these, voice commands only reply when the app is closed) ----

    private val promptedSetup = mutableSetOf<String>()

    private fun checkBackgroundSetup() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) return
        val pm = getSystemService(PowerManager::class.java)
        when {
            !Settings.canDrawOverlays(this) && promptedSetup.add("overlay") -> askSetup(
                "Allow “Display over other apps”",
                "Android blocks opening apps, maps, SMS and calls while Jarvis is in the background. " +
                    "This permission lets Jarvis do them anyway. Keep the floating orb ON.\n\n" +
                    "Xiaomi/Oppo/Vivo/Realme: also enable “Display pop-up windows while running in the background” " +
                    "and Autostart in App info → Permissions.",
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            JarvisAccessibilityService.instance == null && promptedSetup.add("accessibility") -> askSetup(
                "Turn on Jarvis automation",
                "Needed to tap buttons, type, send WhatsApp messages and to check that an app really opened. " +
                    "Open Accessibility → Installed apps → J.A.R.V.I.S. → turn on.",
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            )
            !pm.isIgnoringBatteryOptimizations(packageName) && promptedSetup.add("battery") -> askSetup(
                "Keep Jarvis alive in the background",
                "Without this Android may stop Jarvis when the screen is off.",
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun askSetup(title: String, message: String, settingsIntent: Intent) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Later", null)
            .setPositiveButton("Open settings") { _, _ ->
                try { startActivity(settingsIntent) } catch (e: Exception) {
                    Toast.makeText(this, "Open this setting manually from the Android settings.", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    // ---- Click & Navigation Listeners --------------------------------------------------------

    private fun setupClickListeners() {
        binding.btnMicToggle.setOnClickListener { performHaptic(); listen() }
        binding.reactorView.setOnClickListener { performHaptic(); listen() }
        binding.btnSend.setOnClickListener { performHaptic(); sendTyped() }
        binding.etCommandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
        }
        binding.btnSettings.setOnClickListener { performHaptic(); updateNavSelection(4) }

        // Quick Action Pills below reactor
        binding.pillOpenApps.setOnClickListener { performHaptic(); viewModel.submit("open apps") }
        binding.pillSystemInfo.setOnClickListener { performHaptic(); viewModel.submit("battery status") }
        binding.pillMore.setOnClickListener {
            performHaptic()
            binding.gridControlDeck.visibility = View.VISIBLE
            updateControlDeckUI()
        }
    }

    private fun setupControlDeck() {
        val toggleExpand = {
            performHaptic()
            if (binding.gridControlDeck.visibility == View.VISIBLE) {
                binding.gridControlDeck.visibility = View.GONE
            } else {
                binding.gridControlDeck.visibility = View.VISIBLE
            }
            updateControlDeckUI()
        }

        binding.tvDeckSectionTitle.setOnClickListener { toggleExpand() }

        binding.btnPrevSection.setOnClickListener {
            performHaptic()
            deckSectionIndex = if (deckSectionIndex > 0) deckSectionIndex - 1 else deckSections.size - 1
            binding.gridControlDeck.visibility = View.VISIBLE
            updateControlDeckUI()
        }

        binding.btnNextSection.setOnClickListener {
            performHaptic()
            deckSectionIndex = (deckSectionIndex + 1) % deckSections.size
            binding.gridControlDeck.visibility = View.VISIBLE
            updateControlDeckUI()
        }

        updateControlDeckUI()
    }

    private fun updateControlDeckUI() {
        val isExpanded = binding.gridControlDeck.visibility == View.VISIBLE
        val arrow = if (isExpanded) "▲" else "▼"
        binding.tvDeckSectionTitle.text = "${deckSections[deckSectionIndex]} $arrow"

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
                binding.btnDeckAction6.setOnClickListener { performHaptic(); updateNavSelection(1) }
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
                binding.btnDeckAction4.setOnClickListener { performHaptic(); updateNavSelection(3) }

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
        }

        binding.navTabs.setOnClickListener {
            performHaptic()
            updateNavSelection(1)
        }

        binding.navCentralReactorBtn.setOnClickListener {
            performHaptic()
            updateNavSelection(0)
            listen()
        }

        binding.navHistory.setOnClickListener {
            performHaptic()
            updateNavSelection(3)
        }

        binding.navSettings.setOnClickListener {
            performHaptic()
            updateNavSelection(4)
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

        // Switch active page view
        binding.homeViewLayout.visibility = if (index == 0) View.VISIBLE else View.GONE
        binding.tabsViewLayout.visibility = if (index == 1) View.VISIBLE else View.GONE
        binding.historyViewLayout.visibility = if (index == 3) View.VISIBLE else View.GONE
        binding.settingsViewLayout.visibility = if (index == 4) View.VISIBLE else View.GONE
    }

    private fun setupTabsScreen() {
        tabAdapter = TabAdapter(
            activeTabsList,
            onTabClick = { tab ->
                performHaptic()
                viewModel.submit("open ${tab.title}")
            },
            onTabClose = { tab ->
                performHaptic()
                activeTabsList.remove(tab)
                tabAdapter.updateTabs(activeTabsList)
            }
        )

        binding.rvTabCards.layoutManager = LinearLayoutManager(this)
        binding.rvTabCards.adapter = tabAdapter

        binding.btnMainCloseAllTabs.setOnClickListener {
            performHaptic()
            activeTabsList.clear()
            tabAdapter.updateTabs(emptyList())
        }

        binding.btnAddTab.setOnClickListener {
            performHaptic()
            val newId = (activeTabsList.size + 1).toString()
            activeTabsList.add(BrowserTab(newId, "New Tab", "www.google.com", R.drawable.ic_search))
            tabAdapter.updateTabs(activeTabsList)
        }
    }

    private fun setupSettingsScreen() {
        binding.btnOpenFullSettings.setOnClickListener {
            performHaptic()
            showSettings()
        }

        binding.setRowVoiceLanguage.setOnClickListener {
            performHaptic()
            Toast.makeText(this, "Voice Language: Marathi & English auto-detect", Toast.LENGTH_SHORT).show()
        }

        binding.setRowWakeWord.setOnClickListener {
            performHaptic()
            Toast.makeText(this, "Offline Wake Word: 'Hey Jarvis' active", Toast.LENGTH_SHORT).show()
        }
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

        // Show Robot Avatar inside Arc Reactor when processing / busy
        binding.reactorView.showRobotAvatar = s.busy

        // Reply text update
        val executed = s.lastAction?.takeIf { it.isNotBlank() && it != s.reply }
        val latestReply = if (executed != null) "${s.reply}\n\n⚙ $executed" else s.reply
        binding.tvResponseText.text = latestReply
        binding.tvHistoryLog.text = "• Latest Response:\n$latestReply\n\n• Status: ${if (s.busy) "Processing" else "Ready"}"

        binding.tvAssistantStatus.text = when {
            s.busy -> s.note ?: "Processing command..."
            s.mode == Mode.COMMAND -> "Listening..."
            !s.note.isNullOrBlank() -> s.note
            s.mode == Mode.WAKE -> "Tap to Speak"
            else -> "Tap to Speak"
        }

        val (label, color) = when (s.connection) {
            Connection.PHONE_READY -> "PHONE READY" to "#00E676"
            Connection.NO_AI -> "NO AI KEY" to "#FFB300"
            Connection.PC_ONLINE -> "Connected • PC Online" to "#00E5FF"
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
