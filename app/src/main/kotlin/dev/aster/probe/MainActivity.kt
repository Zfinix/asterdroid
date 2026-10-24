package dev.aster.probe

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.aster.probe.ui.AsterTheme
import dev.aster.probe.ui.ChatScreen
import dev.aster.probe.ui.ClientsScreen
import dev.aster.probe.ui.ConnectScreen
import dev.aster.probe.ui.EnvEntry
import dev.aster.probe.ui.OnboardingScreen
import dev.aster.probe.ui.PairScreen
import dev.aster.probe.ui.Reachable
import dev.aster.probe.ui.Readiness
import dev.aster.probe.ui.ScanScreen
import dev.aster.probe.ui.SetupConfirmSheet
import dev.aster.probe.ui.summarise
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
    private var resumed by mutableStateOf(false)
    private var channel by mutableStateOf(Channel.APP)
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
    private var step by mutableStateOf(Onboarding.Step.WELCOME)
    private var servers by mutableStateOf<List<Lan.Server>?>(null)
    private var scanningLan by mutableStateOf(false)
    private var onWifi by mutableStateOf(true)
    /** What a step is waiting on, as the words its button shows meanwhile. */
    private var busy by mutableStateOf<String?>(null)
    private var problem by mutableStateOf<String?>(null)
    private var readiness by mutableStateOf(Readiness(false, false, false, false, false, false, 0))
    private var signIn: Onboarding.OpenRouter? = null
    private var askedPosting = false
    /** A setup link waiting for its yes, from a tap, a scan or a paste. */
    private var setup by mutableStateOf<SetupLink.Setup?>(null)
    private var scanFrom by mutableStateOf<Page>(Page.Home)
    private var connectFrom by mutableStateOf<Page>(Page.Home)
    private var camera by mutableStateOf(false)
    private var scanProblem by mutableStateOf<String?>(null)
    private var reach by mutableStateOf(Reachable(null, null, "", "", false, null, false, false, false))
    private var pairCode by mutableStateOf<Relay.Code?>(null)
    private var pairing by mutableStateOf(false)
    private var pairProblem by mutableStateOf<String?>(null)
    private var clients by mutableStateOf<List<Relay.Client>?>(null)
    private var loadingClients by mutableStateOf(false)
    private var clientsProblem by mutableStateOf<String?>(null)

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
        // A phone set up by hand or over adb before onboarding existed has
        // nothing to walk through; anyone else starts at the welcome.
        if (!Onboarding.done(this)) {
            val resumed = Onboarding.step(this)
            when {
                resumed != null -> {
                    step = resumed
                    page = Page.Onboarding
                }
                Onboarding.alreadySetUp(this) -> Onboarding.finish(this)
                else -> {
                    Onboarding.saveStep(this, step)
                    page = Page.Onboarding
                }
            }
        }
        refresh()
        handle(intent)
        setContent {
            AsterTheme {
                BackHandler(enabled = page != Page.Home || reading != null || viewing != null) { back() }
                SideEffect { Chat.seen(resumed && page == Page.Chat) }
                LaunchedEffect(page, Chat.open) { if (page == Page.Chat && Chat.open) Chat.warm() }
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
                        onChat = { page = Page.Chat },
                        onConnect = { openConnect(Page.Home) },
                        onHistory = ::openHistory,
                        onSettings = { page = Page.Settings },
                    )
                    Page.Chat -> ChatScreen(
                        items = Chat.items,
                        title = Chat.title,
                        running = AsterAgentService.live,
                        open = Chat.open,
                        busy = Chat.busy,
                        starting = Chat.starting,
                        off = if (channel == Channel.NONE) {
                            "${Env.CHANNEL} is none, so no agent runs on this phone. Set it to app in settings to chat here."
                        } else {
                            null
                        },
                        keyboardBlocked = grants.any { it.id == GrantId.KEYBOARD && it.on },
                        onSend = Chat::send,
                        onStop = Chat::stop,
                        onAnswer = { key, option -> Chat.answer(key, option) },
                        onFresh = Chat::fresh,
                        onStart = ::toggle,
                        onKeyboard = { getSystemService(InputMethodManager::class.java).showInputMethodPicker() },
                        onBack = ::back,
                    )
                    Page.Settings -> SettingsScreen(
                        entries = env,
                        raw = envText,
                        onSet = ::setEnv,
                        onRaw = ::replaceEnv,
                        onConnect = { openConnect(Page.Settings) },
                        onScan = { openScan(Page.Settings) },
                        onSetUp = ::setUpAgain,
                        onBack = ::back,
                    )
                    Page.Onboarding -> OnboardingScreen(
                        step = step,
                        providers = providers,
                        keys = providerKeys,
                        brain = brainLabel(),
                        channel = channel,
                        servers = servers,
                        scanning = scanningLan,
                        onWifi = onWifi,
                        busy = busy,
                        problem = problem,
                        readiness = readiness,
                        onStep = ::goTo,
                        onFree = ::useFree,
                        onScanLan = ::scanLan,
                        onServer = ::useServer,
                        onKey = ::useKey,
                        onSignIn = ::signInOpenRouter,
                        onCustom = ::useCustom,
                        onChannel = ::useChannel,
                        onGrant = { id -> grants.firstOrNull { it.id == id }?.let(::grant) },
                        onAppInfo = { open(Permissions.appInfo(this)) },
                        onPosting = ::askPosting,
                        onBattery = ::askBattery,
                        onEverything = { Permissions.request(this, Permissions.runtime, Permissions.FOREGROUND) },
                        onScanCode = { openScan(Page.Onboarding) },
                        onLink = ::openLink,
                        onClearProblem = { problem = null },
                        onFinish = ::finishOnboarding,
                    )
                    Page.Connect -> ConnectScreen(
                        reach = reach,
                        pending = RelayStatus.pending,
                        onMcp = { on -> access(Env.MCP_TOKEN, on, "access for other agents") },
                        onAcp = { on -> access(Env.ACP_TOKEN, on, "access for editors") },
                        onWifi = ::overWifi,
                        onRelay = ::useRelay,
                        onPair = ::openPair,
                        onClients = ::openClients,
                        onScan = { openScan(Page.Connect) },
                        onAnswer = { id, yes -> RelayStatus.answer(this, id, yes) },
                        onCopy = ::copy,
                        onBack = ::back,
                    )
                    Page.Pair -> PairScreen(
                        code = pairCode,
                        loading = pairing,
                        problem = pairProblem,
                        pending = RelayStatus.pending,
                        onNew = ::newPairCode,
                        onAnswer = { id, yes -> RelayStatus.answer(this, id, yes) },
                        onCopy = ::copy,
                        onBack = ::back,
                    )
                    Page.Clients -> ClientsScreen(
                        clients = clients,
                        loading = loadingClients,
                        problem = clientsProblem,
                        onRevoke = ::revoke,
                        onRefresh = ::loadClients,
                        onBack = ::back,
                    )
                    Page.Scan -> ScanScreen(
                        camera = camera,
                        problem = scanProblem,
                        onCode = ::scanned,
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
                setup?.let { waiting ->
                    SetupConfirmSheet(
                        summary = remember(waiting) { summarise(waiting, Models.catalog(this), Env.value(this, Env.RELAY_URL)) },
                        onApply = { applySetup(waiting) },
                        onCancel = { setup = null },
                    )
                }
            }
        }
        // Only the one the agent's replies need; the rest wait until someone
        // chooses to give them, on the permissions step or in settings.
        Permissions.request(this, Permissions.firstRun, Permissions.NOTIFY)
        // Both have to exist before the agent goes looking for them.
        binDir()
        installSkill()
        installInstructions()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        when (code) {
            Permissions.FOREGROUND -> Permissions.request(this, Permissions.background, Permissions.BACKGROUND)
            Permissions.CAMERA -> {
                camera = Permissions.granted(this, Manifest.permission.CAMERA)
                page = Page.Scan
            }
        }
        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        registerReceiver(onChange, IntentFilter(ActivityLog.CHANGED), Context.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    override fun onPause() {
        resumed = false
        Chat.seen(false)
        runCatching { unregisterReceiver(onChange) }
        super.onPause()
    }

    /**
     * What the activity was opened for: a chat notification lands on the
     * chat, and a setup link on its confirmation sheet over whatever is open.
     */
    private fun handle(intent: Intent?) {
        if (intent?.action == Chat.OPEN) {
            reading = null
            viewing = null
            page = Page.Chat
        }
        if (intent?.action == Intent.ACTION_VIEW) {
            val link = intent.dataString ?: return
            // Consumed once, so turning the phone does not offer the same link again.
            intent.data = null
            offer(link)?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        }
    }

    /** Check a setup link and put it on its sheet; the reason it was refused otherwise. */
    private fun offer(link: String): String? =
        when (val parsed = SetupLink.parse(link, System.currentTimeMillis() / 1000, SetupLink.seen(this))) {
            is SetupLink.Parsed.Ok -> SetupLink.check(parsed.setup, Models.catalog(this)).also {
                if (it == null) setup = parsed.setup
            }
            is SetupLink.Parsed.Bad -> parsed.why
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
            page == Page.Onboarding && step == Onboarding.Step.WELCOME ->
                if (Onboarding.done(this)) page = Page.Settings else finish()
            page == Page.Onboarding -> goTo(Onboarding.Step.entries[step.ordinal - 1])
            page == Page.Pair || page == Page.Clients -> page = Page.Connect
            page == Page.Connect -> page = connectFrom
            page == Page.Scan -> page = scanFrom
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
            // The catalog's own suggestions, when the provider's list is out of reach.
            val found = Models.models(this, provider)
                .ifEmpty { provider.recommended.map { Models.Model(it, Models.pretty(it)) } }
            runOnUiThread {
                if (chosen?.id != provider.id) return@runOnUiThread
                models = found
                loadingModels = false
            }
        }
    }


    /** The chosen brain as one line, for the steps after it and the summary at the end. */
    private fun brainLabel(): String? {
        if (!Models.picked(this)) return null
        val provider = chosen ?: return null
        return "${provider.name}, ${Models.pretty(model)}"
    }

    private fun goTo(next: Onboarding.Step) {
        problem = null
        step = next
        if (!Onboarding.done(this)) Onboarding.saveStep(this, next)
        refresh()
    }

    /** Network, so on a thread; the gateway's own list decides which free model, with a fallback if it is down. */
    private fun useFree() {
        busy = "Finding a free model"
        thread(name = "aster-free") {
            val picked = Models.chooseFree(this)
            runOnUiThread {
                busy = null
                refresh()
                restartAgent("free ${Models.pretty(picked)}")
                goTo(Onboarding.Step.TALK)
            }
        }
    }

    private fun scanLan() {
        if (scanningLan) return
        scanningLan = true
        thread(name = "aster-lan") {
            val found = Lan.scan(this)
            val wifi = Lan.onWifi(this)
            runOnUiThread {
                servers = found
                onWifi = wifi
                scanningLan = false
            }
        }
    }

    private fun useServer(server: Lan.Server, picked: String) {
        Models.chooseCustom(this, server.url, picked, null)
        refresh()
        restartAgent("${server.name} / $picked")
        goTo(Onboarding.Step.TALK)
    }

    /** A key is tried before it is kept: a typo found now is not a failure an hour into a task. */
    private fun useKey(provider: Models.Provider, key: String, check: Boolean) {
        val name = provider.keyVars.firstOrNull() ?: return
        val keep = {
            Env.set(this, name, key)
            Models.choose(this, provider)
            refresh()
            restartAgent("${provider.name} / ${Models.currentModel(this, provider)}")
            goTo(Onboarding.Step.TALK)
        }
        if (!check) return keep()
        busy = "Checking the key"
        problem = null
        thread(name = "aster-key") {
            val why = Models.check(provider.baseUrl, provider.id, key)
            runOnUiThread {
                busy = null
                if (why == null) keep() else problem = why
            }
        }
    }

    private fun useCustom(url: String, picked: String, key: String, check: Boolean) {
        if (!Lan.allowedUrl(url)) return
        val keep = {
            Models.chooseCustom(this, url, picked, key)
            refresh()
            restartAgent("your server / $picked")
            goTo(Onboarding.Step.TALK)
        }
        if (!check) return keep()
        busy = "Checking the address"
        problem = null
        thread(name = "aster-custom") {
            val why = Models.check(url, Models.CUSTOM, key)
            runOnUiThread {
                busy = null
                if (why == null) keep() else problem = why
            }
        }
    }

    /**
     * The browser does the signing in and comes back to the phone's loopback
     * with a code. Pressing the door again while it waits gives up, because
     * someone who closed the browser should not be stuck for five minutes.
     */
    private fun signInOpenRouter() {
        signIn?.let {
            it.close()
            signIn = null
            busy = null
            return
        }
        val provider = providers.firstOrNull { it.id == "openrouter" } ?: return
        val flow = runCatching { Onboarding.OpenRouter.start() }.getOrElse {
            problem = "Droid could not wait for OpenRouter: $it"
            return
        }
        signIn = flow
        busy = "Waiting for OpenRouter. Tap to stop."
        problem = null
        openLink(flow.url)
        thread(name = "aster-signin") {
            val key = runCatching { flow.use { it.await() } }.getOrNull()
            runOnUiThread {
                if (signIn !== flow) return@runOnUiThread
                signIn = null
                busy = null
                if (key == null) {
                    problem = "OpenRouter did not send a key back. Try again, or paste a key instead."
                } else {
                    useKey(provider, key, check = false)
                }
            }
        }
    }

    /** A channel and what it needs, in one write. "None" still lets agents in, so it gets an MCP key. */
    private fun useChannel(picked: Channel, values: Map<String, String>) {
        val write = values + (Env.CHANNEL to picked.id)
        val withKey = if (picked == Channel.NONE && Env.value(this, Env.MCP_TOKEN) == null) {
            write + (Env.MCP_TOKEN to SetupLink.token())
        } else {
            write
        }
        Env.setAll(this, withKey)
        refresh()
        restartAgent(picked.label)
        goTo(Onboarding.Step.PERMISSIONS)
    }

    /** Asked once the system way; after a refusal the system stays silent, so the app's notification page opens instead. */
    private fun askPosting() {
        val missing = Permissions.missing(this, Permissions.firstRun)
        if (missing.isEmpty()) return
        if (askedPosting && missing.none { shouldShowRequestPermissionRationale(it) }) {
            open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } else {
            askedPosting = true
            Permissions.request(this, Permissions.firstRun, Permissions.NOTIFY)
        }
    }

    private fun askBattery() {
        if (Permissions.unrestrictedBattery(this)) return
        if (!open(Permissions.batteryPrompt(this))) open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /** The agent starts at the end, so the first thing that works is the chat itself. */
    private fun finishOnboarding() {
        Onboarding.finish(this)
        if (!AsterAgentService.live) {
            AsterAgentService.start(this)
            ActivityLog.state(this, "Starting")
        }
        page = if (channel == Channel.NONE) {
            connectFrom = Page.Home
            Page.Connect
        } else {
            Page.Chat
        }
        refresh()
    }

    private fun setUpAgain() {
        step = Onboarding.Step.WELCOME
        servers = null
        problem = null
        page = Page.Onboarding
        refresh()
    }

    /** The scanner asks for the camera only now, the first time someone wants to scan. */
    private fun openScan(from: Page) {
        scanFrom = from
        scanProblem = null
        camera = Permissions.granted(this, Manifest.permission.CAMERA)
        if (camera) page = Page.Scan else Permissions.request(this, listOf(Manifest.permission.CAMERA), Permissions.CAMERA)
    }

    /**
     * A setup link goes to its sheet; a computer's pairing QR goes straight
     * to the relay, since scanning it on purpose is the consent; anything
     * else is said to be neither.
     */
    private fun scanned(text: String) {
        when {
            SetupLink.isSetupLink(text) -> {
                scanProblem = offer(text)
                if (scanProblem == null) page = scanFrom
            }
            CLAIM in text -> {
                if (!reach.selfHosted) {
                    scanProblem = "That is a computer's pairing code, which needs Aster Relay. Relay is coming " +
                        "soon. Until then, connect over USB, Wi-Fi or Tailscale in Connect a computer."
                    return
                }
                if (!reach.relay) {
                    scanProblem = "That is a computer's pairing code. Turn on Reach from anywhere in Connect a " +
                        "computer first, then scan it again."
                    return
                }
                scanProblem = "Pairing with the computer"
                thread(name = "aster-claim") {
                    val reply = Relay.add(text)
                    runOnUiThread {
                        when (reply) {
                            is Relay.Reply.Ok -> {
                                scanProblem = null
                                Toast.makeText(this, "The computer is paired.", Toast.LENGTH_LONG).show()
                                openClients()
                            }
                            is Relay.Reply.Failed -> scanProblem = reply.why
                        }
                    }
                }
            }
            else -> scanProblem = "That code is not a Droid setup or pairing code."
        }
    }

    /**
     * One batch to `.env`, one restart. When the link also pairs a browser,
     * that waits for the relay to come up, since the connector it goes
     * through only starts with the agent.
     */
    private fun applySetup(confirmed: SetupLink.Setup) {
        setup = null
        val ok = runCatching { SetupLink.apply(this, confirmed) }
        ok.exceptionOrNull()?.let {
            Toast.makeText(this, it.message ?: "Droid could not use this setup link.", Toast.LENGTH_LONG).show()
            return
        }
        refresh()
        if (AsterAgentService.live) restartAgent("the setup link") else ActivityLog.state(this, "Set up from a link")
        if (page == Page.Onboarding) goTo(Onboarding.Step.PERMISSIONS)
        confirmed.claim?.let { claim ->
            val relay = confirmed.relayUrl ?: Env.value(this, Env.RELAY_URL)?.takeIf(Relay::selfHosted)
            if (relay == null) {
                ActivityLog.state(this, "${claim.name} was not paired: Aster Relay is coming soon.")
                return
            }
            if (!AsterAgentService.live && Onboarding.done(this)) AsterAgentService.start(this)
            thread(name = "aster-claim") {
                val deadline = System.currentTimeMillis() + CLAIM_WAIT_MS
                var reply: Relay.Reply<Unit> = Relay.Reply.Failed("The relay did not come up in time.")
                while (System.currentTimeMillis() < deadline) {
                    reply = Relay.add(claim.link(relay))
                    if (reply is Relay.Reply.Ok) break
                    Thread.sleep(CLAIM_RETRY_MS)
                }
                val said = when (reply) {
                    is Relay.Reply.Ok -> "${claim.name} is paired."
                    is Relay.Reply.Failed -> "${claim.name} was not paired: ${reply.why}"
                }
                ActivityLog.state(this, said)
            }
        }
    }

    private fun openConnect(from: Page) {
        connectFrom = from
        page = Page.Connect
        refresh()
    }

    /** A new 64-hex key when turned on, none when off. Whoever holds the key holds the phone. */
    private fun access(name: String, on: Boolean, what: String) {
        val values = mutableMapOf(name to if (on) SetupLink.token() else "")
        if (on && reach.wifi) reach.address?.let { ip -> values[bindFor(name)] = ip }
        Env.setAll(this, values)
        reconnect(if (on) what else "no $what")
    }

    private fun bindFor(token: String) = if (token == Env.MCP_TOKEN) Env.MCP_BIND else Env.ACP_BIND

    /**
     * The Wi-Fi address rather than every interface: 0.0.0.0 would also
     * listen on mobile data, where a carrier may hand out a public IPv6.
     */
    private fun overWifi(on: Boolean) {
        val ip = Lan.address(this)?.first
        if (on && ip == null) {
            Toast.makeText(this, "This phone is not on Wi-Fi.", Toast.LENGTH_LONG).show()
            return
        }
        val bind = if (on) ip.orEmpty() else ""
        Env.setAll(this, mapOf(Env.MCP_BIND to bind, Env.ACP_BIND to bind))
        reconnect(if (on) "access over Wi-Fi" else "access over USB only")
    }

    private fun useRelay(on: Boolean) {
        if (on && !reach.selfHosted) return
        Env.set(this, Env.RELAY, if (on) "on" else "")
        reconnect(if (on) "Aster Relay" else "no relay")
    }

    /** Computers can only connect while Droid runs, so a change here starts it if it is not already up. */
    private fun reconnect(onWhat: String) {
        if (AsterAgentService.live) {
            restartAgent(onWhat)
        } else {
            AsterAgentService.start(this)
            ActivityLog.state(this, "Starting with $onWhat")
        }
        refresh()
    }

    private fun openPair() {
        page = Page.Pair
        if (pairCode?.takeIf { it.expires > System.currentTimeMillis() / 1000 } == null) newPairCode()
    }

    private fun newPairCode() {
        pairing = true
        pairProblem = null
        thread(name = "aster-pair") {
            val reply = Relay.pair()
            runOnUiThread {
                pairing = false
                when (reply) {
                    is Relay.Reply.Ok -> pairCode = reply.value
                    is Relay.Reply.Failed -> {
                        pairCode = null
                        pairProblem = reply.why
                    }
                }
            }
        }
    }

    private fun openClients() {
        page = Page.Clients
        loadClients()
    }

    private fun loadClients() {
        loadingClients = true
        clientsProblem = null
        thread(name = "aster-clients") {
            val reply = Relay.clients()
            runOnUiThread {
                loadingClients = false
                when (reply) {
                    is Relay.Reply.Ok -> clients = reply.value
                    is Relay.Reply.Failed -> clientsProblem = reply.why
                }
            }
        }
    }

    private fun revoke(id: String) {
        loadingClients = true
        thread(name = "aster-revoke") {
            val reply = Relay.revoke(id)
            runOnUiThread {
                if (reply is Relay.Reply.Failed) clientsProblem = reply.why
                loadClients()
            }
        }
    }

    /**
     * Keys are marked sensitive, so Android 13+ hides them in its clipboard
     * preview. Older phones show nothing on copy, so they get a toast.
     */
    private fun copy(label: String, text: String, secret: Boolean) {
        val clip = ClipData.newPlainText(label, text)
        if (secret) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ClipDescription.EXTRA_IS_SENSITIVE else SENSITIVE, true)
            }
        }
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun openLink(url: String) {
        if (!open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
            Toast.makeText(this, "No browser on this phone opened $url", Toast.LENGTH_LONG).show()
        }
    }

    private fun open(intent: Intent): Boolean = runCatching { startActivity(intent) }.isSuccess

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
        channel = Channel.current(this)
        env = envEntries()
        envText = Env.text(this)
        val values = Env.all(this)
        val bound = listOfNotNull(values[Env.MCP_BIND], values[Env.ACP_BIND]).any { it != LOOPBACK }
        reach = Reachable(
            mcpToken = values[Env.MCP_TOKEN]?.takeIf { it.length >= MIN_TOKEN },
            acpToken = values[Env.ACP_TOKEN]?.takeIf { it.length >= MIN_TOKEN },
            mcpPort = values[Env.MCP_PORT] ?: MCP_PORT,
            acpPort = values[Env.ACP_PORT] ?: ACP_PORT,
            wifi = bound,
            address = if (page == Page.Connect) Lan.address(this)?.first else reach.address,
            relay = values[Env.RELAY].equals("on", ignoreCase = true) && Relay.selfHosted(values[Env.RELAY_URL]),
            selfHosted = Relay.selfHosted(values[Env.RELAY_URL]),
            running = AsterAgentService.live,
        )
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
        // Read only where it is shown: refresh runs on every line the agent prints.
        if (page == Page.Onboarding) {
            readiness = Readiness(
                screen = grants[0].on,
                keyboard = grants[1].on,
                listener = grants[2].on,
                posting = Permissions.missing(this, Permissions.firstRun).isEmpty(),
                battery = Permissions.unrestrictedBattery(this),
                restricted = Permissions.restricted(this),
                ungranted = Permissions.ungranted(this),
            )
        }
    }

    /**
     * What the phone cannot be reached without: the chat app, what that app
     * needs, whoever is allowed to message it, and a key for the chosen
     * provider. Everything else the file already carries follows it, because a
     * key set for a provider that is not the current one was held but never
     * shown, and so looked lost.
     */
    private fun envEntries(): List<EnvEntry> {
        val values = Env.all(this)
        val provider = chosen
        fun row(name: String, label: String, secret: Boolean, need: String) =
            EnvEntry(name = name, label = label, value = values[name].orEmpty(), secret = secret, need = need)
        val reach = when (Channel.current(this)) {
            Channel.TELEGRAM -> listOf(
                row(
                    Env.TELEGRAM_TOKEN,
                    "Telegram token",
                    secret = true,
                    need = "The bridge needs a bot token from @BotFather before anyone can reach this phone.",
                ),
                row(
                    Env.REMOTE_USERS,
                    "Allowed ids",
                    secret = false,
                    need = "Message the bot once: it replies with your id, which goes here. " +
                        "Until then it answers nobody.",
                ),
            )
            Channel.PHOTON -> listOf(
                row(Env.PHOTON_KEY, "Photon key", secret = true, need = "The project key from Photon, starting pho_sk_."),
                row(Env.PHOTON_PROJECT, "Photon project", secret = false, need = "The project id from Photon, starting pho_prj_."),
                row(
                    Env.REMOTE_USERS,
                    "Allowed people",
                    secret = false,
                    need = "The handles allowed to message the agent, separated by commas. Until then it answers nobody.",
                ),
            )
            Channel.APP, Channel.NONE -> emptyList()
        }
        val first = listOf(
            row(
                Env.CHANNEL,
                "Chat app",
                secret = false,
                need = "Where people talk to the agent: app, telegram, photon or none. Left unset, it is " +
                    "telegram when a bot token is set, and the app otherwise.",
            ),
        ) + reach + listOfNotNull(
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
        providers.firstOrNull { name in it.keyVars }?.let { "${it.name} key" }
            ?: ENV_LABELS[name]
            ?: name

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
                    "Open Settings and turn on ${grant.label} for Droid."
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
        const val LOOPBACK = "127.0.0.1"
        const val MCP_PORT = "7071"
        const val ACP_PORT = "7072"
        /** Matches asterctl and the service, which refuse a shorter key. */
        const val MIN_TOKEN = 32
        /** What a computer's own pairing QR carries: `https://<relay>/claim#1...`. */
        const val CLAIM = "/claim#"
        const val CLAIM_WAIT_MS = 60_000L
        const val CLAIM_RETRY_MS = 3_000L
        /** ClipDescription.EXTRA_IS_SENSITIVE before Android 13 named it, which some keyboards already honour. */
        const val SENSITIVE = "android.content.extra.IS_SENSITIVE"
        const val LISTENER_SETTINGS = "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
        // Public since Android 11, but never added to the SDK's Settings class.
        const val A11Y_DETAIL = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
        val SECRETISH = listOf("KEY", "TOKEN", "SECRET", "PASSWORD")
        val ENV_LABELS = mapOf(
            Env.AGENT_CMD to "Agent command",
            Env.MCP_TOKEN to "Other agents' key",
            Env.MCP_PORT to "Other agents' port",
            Env.MCP_BIND to "Other agents' address",
            Env.ACP_TOKEN to "Editors' key",
            Env.ACP_PORT to "Editors' port",
            Env.ACP_BIND to "Editors' address",
            Env.RELAY to "Aster Relay",
            Env.RELAY_URL to "Relay address",
            Env.TELEGRAM_TOKEN to "Telegram token",
            Env.PHOTON_KEY to "Photon key",
            Env.PHOTON_PROJECT to "Photon project",
            Env.REMOTE_USERS to "Allowed people",
        )
    }
}
