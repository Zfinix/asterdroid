package dev.aster.probe

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.aster.probe.ui.AsterTheme
import dev.aster.probe.ui.EnvEntry
import dev.aster.probe.ui.SettingsScreen
import dev.aster.probe.ui.Grant
import dev.aster.probe.ui.GrantId
import dev.aster.probe.ui.HistoryScreen
import dev.aster.probe.ui.ProbeScreen
import dev.aster.probe.ui.SkillFile
import dev.aster.probe.ui.skillMeta
import java.io.File
import kotlin.concurrent.thread

/** A window onto the run: what is granted, and what the agent just did. */
class MainActivity : ComponentActivity() {

    private var entries by mutableStateOf(ActivityLog.recent())
    private var state by mutableStateOf(ActivityLog.state)
    private var grants by mutableStateOf(emptyList<Grant>())
    private var chosen by mutableStateOf<Models.Provider?>(null)
    private var providers by mutableStateOf(emptyList<Models.Provider>())
    private var providerKeys by mutableStateOf(emptySet<String>())
    private var model by mutableStateOf("")
    private var effort by mutableStateOf("")
    private var models by mutableStateOf(emptyList<Models.Model>())
    private var env by mutableStateOf(emptyList<EnvEntry>())
    private var envText by mutableStateOf("")
    private var loadingModels by mutableStateOf(false)
    private var page by mutableStateOf<Page>(Page.Home)
    private var sessions by mutableStateOf(emptyList<Session>())
    private var skills by mutableStateOf(emptyList<LearnedSkill>())
    private var notes by mutableStateOf(emptyList<Note>())
    private var reading by mutableStateOf<SkillFile?>(null)
    private var loadingSessions by mutableStateOf(false)
    private var historyQuery by mutableStateOf("")
    private var matches by mutableStateOf<Map<String, Int>?>(null)
    private var viewing by mutableStateOf<Session?>(null)
    private var sessionLines by mutableStateOf<List<Entry>?>(null)
    private val linesById = HashMap<String, List<Entry>>()

    private val onChange = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The screen is dark whatever the system theme is, so ask for light
        // status icons; the default follows the system and hides them on black.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // Each opening of the app starts a clean feed; the transcripts hold the past.
        ActivityLog.clear(null)
        refresh()
        setContent {
            AsterTheme {
                BackHandler(enabled = page != Page.Home || reading != null || viewing != null) { back() }
                when (val current = page) {
                    Page.Home -> ProbeScreen(
                        running = AsterAgentService.live,
                        grants = grants,
                        entries = entries,
                        chosen = chosen,
                        providers = providers,
                        providerKeys = providerKeys,
                        model = model,
                        models = models,
                        effort = effort,
                        loadingModels = loadingModels,
                        onGrant = ::grant,
                        onProvider = ::chooseProvider,
                        onModel = ::chooseModel,
                        onEffort = ::chooseEffort,
                        onToggle = ::toggle,
                        onHistory = ::openHistory,
                        onSettings = { page = Page.Settings },
                    )
                    Page.Settings -> SettingsScreen(
                        entries = env,
                        raw = envText,
                        onSet = ::setEnv,
                        onRaw = ::replaceEnv,
                        onBack = ::back,
                    )
                    Page.History -> HistoryScreen(
                        sessions = sessions,
                        matches = matches,
                        skills = skills,
                        notes = notes,
                        query = historyQuery,
                        loading = loadingSessions,
                        onQuery = ::searchSessions,
                        onOpen = ::openSession,
                        reading = reading,
                        viewing = viewing,
                        viewingLines = sessionLines,
                        onCloseViewing = { viewing = null },
                        onOpenSkill = { read(it.name, skillMeta(it), it.file) },
                        onOpenNote = { read(it.name, it.description.ifEmpty { "Remembered" }, it.file) },
                        onCloseReading = { reading = null },
                        onBack = ::back,
                    )
                }
            }
        }
        Permissions.request(this, Permissions.runtime, Permissions.FOREGROUND)
        // Both have to exist before the agent goes looking for them.
        binDir()
        installSkill()
        installInstructions()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == Permissions.FOREGROUND) {
            Permissions.request(this, Permissions.background, Permissions.BACKGROUND)
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(onChange, IntentFilter(ActivityLog.CHANGED), Context.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    override fun onPause() {
        runCatching { unregisterReceiver(onChange) }
        super.onPause()
    }

    private fun openHistory() {
        page = Page.History
        loadSessions()
    }

    private fun openSession(session: Session) {
        sessionLines = linesById[session.id]
        viewing = session
        if (sessionLines == null) {
            thread(name = "aster-session") {
                val lines = Sessions.entries(session.file)
                runOnUiThread {
                    linesById[session.id] = lines
                    if (viewing?.id == session.id) sessionLines = lines
                }
            }
        }
    }

    private fun read(title: String, meta: String, file: File) {
        reading = SkillFile(title, meta, null)
        thread(name = "aster-read") {
            val lines = Sessions.read(file)
            runOnUiThread { if (reading?.name == title) reading = SkillFile(title, meta, lines) }
        }
    }

    private fun back() {
        when {
            reading != null -> reading = null
            viewing != null -> viewing = null
            else -> page = Page.Home
        }
    }

    /** Transcripts are hundreds of kilobytes; read them off the main thread, once per open. */
    private fun loadSessions() {
        loadingSessions = true
        synchronized(linesById) { linesById.clear() }
        thread(name = "aster-sessions") {
            val found = Sessions.list(this)
            val known = Sessions.skills(this)
            val remembered = Sessions.notes(this)
            runOnUiThread {
                sessions = found
                skills = known
                notes = remembered
                loadingSessions = false
                if (historyQuery.isNotBlank()) searchSessions(historyQuery)
            }
        }
    }

    /** A search reads every session's lines the first time, then works from the cache. */
    private fun searchSessions(query: String) {
        historyQuery = query
        if (query.isBlank()) {
            matches = null
            return
        }
        val wanted = sessions
        thread(name = "aster-search") {
            val hits = HashMap<String, Int>()
            for (session in wanted) {
                val lines = synchronized(linesById) {
                    linesById.getOrPut(session.id) { Sessions.entries(session.file) }
                }
                val count = lines.count { it.text.contains(query, ignoreCase = true) } +
                    (if (session.label.contains(query, ignoreCase = true)) 1 else 0)
                if (count > 0) hits[session.id] = count
            }
            runOnUiThread { if (historyQuery == query) matches = hits }
        }
    }

    private fun toggle() {
        if (AsterAgentService.live) {
            AsterAgentService.stop(this)
            ActivityLog.state(this, "Stopped")
        } else {
            AsterAgentService.start(this)
            ActivityLog.state(this, "Starting")
        }
        refresh()
    }

    /**
     * The model is read once when the agent process starts, so a running agent
     * keeps the old one until it is restarted. Doing that here means the choice
     * takes effect rather than sitting in a preference nobody reads.
     */
    private fun chooseProvider(provider: Models.Provider) {
        Models.choose(this, provider)
        chosen = provider
        restartAgent("${provider.name} / ${Models.currentModel(this, provider)}")
        refresh()
        fetchModels(provider)
    }

    private fun chooseModel(picked: String) {
        val provider = chosen ?: return
        Models.chooseModel(this, provider, picked)
        model = picked
        restartAgent("${provider.name} / $picked")
    }

    /**
     * The keys the agent runs on, written from the phone. The child reads them
     * once at startup, so this restarts it for the same reason the model picker
     * does: a key that only lands in a file nobody rereads has not been set.
     */
    private fun setEnv(name: String, value: String) {
        Env.set(this, name, value)
        val label = env.firstOrNull { it.name == name }?.label ?: name
        refresh()
        restartAgent(if (value.isBlank()) "$label cleared" else "the new $label")
    }

    /**
     * The whole file at once, for a name no row covers. Same restart as [setEnv],
     * for the same reason: the child reads the file only as it starts.
     */
    private fun replaceEnv(text: String) {
        Env.replace(this, text)
        refresh()
        restartAgent("the edited .env")
    }

    private fun chooseEffort(picked: String) {
        Models.chooseEffort(this, picked)
        effort = picked
        restartAgent("effort ${picked.ifEmpty { "default" }}")
    }

    private fun restartAgent(onWhat: String) {
        if (AsterAgentService.live) {
            AsterAgentService.stop(this)
            AsterAgentService.start(this)
            ActivityLog.state(this, "Restarting with $onWhat")
        } else {
            ActivityLog.state(this, "The next start uses $onWhat")
        }
    }

    /** Network, so off the main thread; a late answer for an old provider is dropped. */
    private fun fetchModels(provider: Models.Provider) {
        loadingModels = true
        models = emptyList()
        thread(name = "aster-models") {
            val found = Models.models(this, provider)
            runOnUiThread {
                if (chosen?.id != provider.id) return@runOnUiThread
                models = found
                loadingModels = false
            }
        }
    }

    private fun refresh() = runOnUiThread {
        entries = ActivityLog.recent()
        if (page == Page.History) loadSessions()
        state = ActivityLog.state
        providers = Models.offered(this)
        providerKeys = Models.present(this)
        val was = chosen?.id
        chosen = Models.current(this)
        model = chosen?.let { Models.currentModel(this, it) }.orEmpty()
        effort = Models.currentEffort(this)
        if (chosen != null && chosen?.id != was) fetchModels(chosen!!)
        env = envEntries()
        envText = Env.text(this)
        grants = listOf(
            Grant(
                GrantId.SCREEN,
                "Screen",
                granted("enabled_accessibility_services"),
                "cannot see or tap the screen",
            ),
            Grant(
                GrantId.KEYBOARD,
                "Keyboard",
                granted("default_input_method"),
                "cannot type",
            ),
            Grant(
                GrantId.NOTIFICATIONS,
                "Notifications",
                granted("enabled_notification_listeners"),
                "cannot read notifications",
            ),
        )
    }

    /**
     * What the phone cannot be reached without: a bot token, whoever is allowed
     * to message it, and a key for the chosen provider. Everything else the
     * file already carries follows it, because a key set for a provider that is
     * not the current one was held but never shown, and so looked lost.
     */
    private fun envEntries(): List<EnvEntry> {
        val values = Env.all(this)
        val provider = chosen
        val first = listOfNotNull(
            EnvEntry(
                name = Env.TELEGRAM_TOKEN,
                label = "Telegram token",
                value = values[Env.TELEGRAM_TOKEN].orEmpty(),
                secret = true,
                need = "The bridge needs a bot token from @BotFather before anyone can reach this phone.",
            ),
            EnvEntry(
                name = Env.REMOTE_USERS,
                label = "Allowed ids",
                value = values[Env.REMOTE_USERS].orEmpty(),
                secret = false,
                need = "Message the bot once: it replies with your id, which goes here. " +
                    "Until then it answers nobody.",
            ),
            provider?.keyVars?.firstOrNull()?.let { key ->
                EnvEntry(
                    name = key,
                    label = "${provider.name} key",
                    value = values[key].orEmpty(),
                    secret = true,
                    need = "Every request to ${provider.name} fails without it.",
                )
            },
        )
        val rest = (values.keys - first.map { it.name }.toSet()).sorted().map { name ->
            EnvEntry(
                name = name,
                label = envLabel(name),
                value = values[name].orEmpty(),
                secret = envSecret(name),
                need = "",
            )
        }
        return first + rest
    }

    /** A provider's key var reads better as the provider than as its own name. */
    private fun envLabel(name: String): String =
        providers.firstOrNull { name in it.keyVars }?.let { "${it.name} key" } ?: name

    /** Anything that reads like a credential is dotted out in the list. */
    private fun envSecret(name: String): Boolean =
        name.uppercase().let { upper -> SECRETISH.any { it in upper } }

    /**
     * None of the three is a runtime permission, so the closest thing to a
     * prompt is the page that holds the switch: the per-app detail screens, and
     * the keyboard picker once the keyboard is enabled but not chosen.
     */
    private fun grant(grant: Grant) {
        if (grant.id == GrantId.KEYBOARD && keyboardEnabled()) {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            return
        }
        val detail = when (grant.id) {
            // The accessibility page reads the component as a parcelable and
            // bounces to the plain list when it is a string; the notification
            // one wants the string. Passing both suits either.
            GrantId.SCREEN -> detail(A11Y_DETAIL) {
                putExtra(Intent.EXTRA_COMPONENT_NAME, component(AsterA11yService::class.java))
            }
            GrantId.NOTIFICATIONS -> detail(
                Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS,
            ) {
                putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    component(AsterNotifications::class.java).flattenToString(),
                )
            }
            GrantId.KEYBOARD -> null
        }
        val fallback = when (grant.id) {
            GrantId.SCREEN -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            GrantId.KEYBOARD -> Settings.ACTION_INPUT_METHOD_SETTINGS
            GrantId.NOTIFICATIONS -> LISTENER_SETTINGS
        }
        // The detail page needs a signature permission on most builds, so the
        // list screen is the usual route; some ROMs then crash inside it, and
        // from here that looks identical to nothing happening.
        val opened = detail != null && runCatching { startActivity(detail) }.isSuccess ||
            runCatching { startActivity(Intent(fallback)) }.isSuccess
        ActivityLog.state(
            this,
            if (opened) {
                "Opened the ${grant.label.lowercase()} settings"
            } else {
                "This phone did not open the ${grant.label.lowercase()} settings. " +
                    "Open Settings and turn on ${grant.label} for Aster."
            },
        )
    }

    /** The per-app page, which only exists from Android 11 on. */
    private fun detail(action: String, address: Intent.() -> Unit): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return Intent(action).apply(address)
    }

    private fun component(service: Class<*>) = ComponentName(this, service)

    /**
     * Android 14 made several of these keys unreadable and throws rather than
     * returning null, so an unguarded read takes the whole activity down before
     * it can open the screen that would fix the grant.
     */
    private fun granted(key: String): Boolean = runCatching {
        Settings.Secure.getString(contentResolver, key).orEmpty().contains(packageName)
    }.getOrElse {
        Log.w(TAG, "cannot read $key: $it")
        false
    }

    /** `enabled_input_methods` is one of the blocked keys; ask the manager. */
    private fun keyboardEnabled(): Boolean = runCatching {
        getSystemService(InputMethodManager::class.java)
            .enabledInputMethodList
            .any { it.packageName == packageName }
    }.getOrDefault(false)

    private fun binDir(): File = Install.binDir(this)

    private fun installSkill() = Install.skill(this)

    private fun installInstructions() = Install.instructions(this)

    private companion object {
        const val TAG = "ASTERPROBE"
        const val LISTENER_SETTINGS = "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
        // Public since Android 11, but never added to the SDK's Settings class.
        const val A11Y_DETAIL = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
        val SECRETISH = listOf("KEY", "TOKEN", "SECRET", "PASSWORD")
    }
}
