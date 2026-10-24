package dev.aster.probe.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aster.probe.Channel
import dev.aster.probe.Env
import dev.aster.probe.Lan
import dev.aster.probe.Models
import dev.aster.probe.Onboarding.Step

/** Which switches the permissions step reads, fresh each time the screen comes back from Settings. */
data class Readiness(
    val screen: Boolean,
    val keyboard: Boolean,
    val listener: Boolean,
    val posting: Boolean,
    val battery: Boolean,
    /** Android 13+ will grey out the screen switch until "Allow restricted settings" is chosen. */
    val restricted: Boolean,
    /** Runtime permissions for calls, messages and the rest that are not given yet. */
    val ungranted: Int,
)

/** A door opened inside a step, so its own back arrow returns to the step's choices. */
private sealed interface Inside {
    data object Free : Inside
    data object Computer : Inside
    data object Keys : Inside
    data class Key(val provider: Models.Provider) : Inside
    data class Custom(val url: String) : Inside
    data object Telegram : Inside
    data object Photon : Inside
}

/**
 * The first run, one decision per screen: a brain, a place to talk to it, the
 * switches it needs, done. Every door works without an account, and the
 * cheapest one is first, so the shortest path to a working agent is to keep
 * pressing the top choice.
 */
@Composable
fun OnboardingScreen(
    step: Step,
    providers: List<Models.Provider>,
    keys: Set<String>,
    brain: String?,
    channel: Channel,
    servers: List<Lan.Server>?,
    scanning: Boolean,
    onWifi: Boolean,
    busy: String?,
    problem: String?,
    readiness: Readiness,
    onStep: (Step) -> Unit,
    onFree: () -> Unit,
    onScanLan: () -> Unit,
    onServer: (Lan.Server, String) -> Unit,
    onKey: (Models.Provider, String, Boolean) -> Unit,
    onSignIn: () -> Unit,
    onCustom: (String, String, String, Boolean) -> Unit,
    onChannel: (Channel, Map<String, String>) -> Unit,
    onGrant: (GrantId) -> Unit,
    onAppInfo: () -> Unit,
    onPosting: () -> Unit,
    onBattery: () -> Unit,
    onEverything: () -> Unit,
    onScanCode: () -> Unit,
    onLink: (String) -> Unit,
    onClearProblem: () -> Unit,
    onFinish: () -> Unit,
) {
    var inside by remember(step) { mutableStateOf<Inside?>(null) }
    BackHandler(enabled = inside != null) {
        inside = when (inside) {
            is Inside.Key, is Inside.Custom -> Inside.Keys
            else -> null
        }
    }
    LaunchedEffect(inside) { onClearProblem() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.ground)
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = Gutter),
    ) {
        if (step != Step.WELCOME) {
            BackRow(
                title = title(step, inside),
                onBack = {
                    when (inside) {
                        null -> onStep(previous(step))
                        is Inside.Key, is Inside.Custom -> inside = Inside.Keys
                        else -> inside = null
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            when (step) {
                Step.WELCOME -> Welcome()
                Step.BRAIN -> when (val at = inside) {
                    null -> Brains(brain, onOpen = { inside = it }, onScanCode = onScanCode)
                    Inside.Free -> Free()
                    Inside.Computer -> Computer(servers, scanning, onWifi, onScanLan, onServer, onLink, onType = {
                        inside = Inside.Custom("http://")
                    })
                    Inside.Keys -> Keys(providers, keys, onOpen = { inside = it }, onSignIn = onSignIn, busy = busy, problem = problem)
                    is Inside.Key -> KeyEntry(at.provider, busy, problem, onKey, onLink)
                    is Inside.Custom -> Custom(at.url, busy, problem, onCustom)
                    else -> Unit
                }
                Step.TALK -> when (inside) {
                    null -> Talk(channel, onOpen = { inside = it }, onChannel = onChannel)
                    Inside.Telegram -> Telegram(onChannel, onLink)
                    Inside.Photon -> Photon(onChannel, onLink)
                    else -> Unit
                }
                Step.PERMISSIONS -> Permissions(readiness, onGrant, onAppInfo, onPosting, onBattery, onEverything)
                Step.DONE -> Done(brain, channel, readiness)
            }
            if (problem != null && inside !is Inside.Key && inside !is Inside.Custom && inside != Inside.Keys) {
                Text(text = problem, style = Type.secondary, color = Ink.red, modifier = Modifier.padding(top = 12.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.padding(top = 8.dp, bottom = Gutter)) {
            when {
                step == Step.WELCOME -> {
                    PrimaryButton(text = "Get started", onClick = { onStep(Step.BRAIN) })
                    QuietButton(text = "I have a setup code", onClick = onScanCode)
                }
                step == Step.BRAIN && inside == Inside.Free -> PrimaryButton(
                    text = busy ?: "Use the free models",
                    enabled = busy == null,
                    onClick = onFree,
                )
                step == Step.BRAIN && inside == null && brain != null ->
                    QuietButton(text = "Keep $brain", onClick = { onStep(Step.TALK) })
                step == Step.TALK && inside == null ->
                    QuietButton(text = "Keep the current choice", onClick = { onStep(Step.PERMISSIONS) })
                step == Step.PERMISSIONS -> PrimaryButton(text = "Continue", onClick = { onStep(Step.DONE) })
                step == Step.DONE -> PrimaryButton(
                    text = if (channel == Channel.NONE) "Finish" else "Start Droid and open the chat",
                    onClick = onFinish,
                )
            }
        }
    }
}

private fun title(step: Step, inside: Inside?): String = when (inside) {
    Inside.Free -> "Free models"
    Inside.Computer -> "Your computer's model"
    Inside.Keys -> "Sign in or use a key"
    is Inside.Key -> inside.provider.name
    is Inside.Custom -> "Any OpenAI-compatible address"
    Inside.Telegram -> "Telegram"
    Inside.Photon -> "iMessage and WhatsApp"
    null -> when (step) {
        Step.WELCOME -> ""
        Step.BRAIN -> "Choose a brain"
        Step.TALK -> "Where to talk to it"
        Step.PERMISSIONS -> "What Droid may use"
        Step.DONE -> "Droid is ready"
    }
}

private fun previous(step: Step): Step = Step.entries[(step.ordinal - 1).coerceAtLeast(0)]

@Composable
private fun Prose(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = Type.body, color = Ink.dim, modifier = modifier.padding(top = 12.dp))
}

@Composable
private fun Welcome() {
    Lockup(Modifier.padding(top = 32.dp))
    Text(
        text = "An agent that uses your phone the way you do.",
        style = MaterialTheme.typography.displaySmall,
        color = Ink.text,
        modifier = Modifier.padding(top = 48.dp),
    )
    Prose(
        "Droid lets an AI agent see the screen, tap, type and open apps, so you can ask for things " +
            "in plain words. It works with any model, including free ones and the one on your own computer.",
        Modifier.padding(top = 8.dp),
    )
    Prose("Setup takes about two minutes, and none of it needs an account.")
}

@Composable
private fun Brains(brain: String?, onOpen: (Inside) -> Unit, onScanCode: () -> Unit) {
    Prose("The model is what thinks. You can change it any time from the home screen.")
    if (brain != null) {
        Text(
            text = "Now: $brain",
            style = Type.secondary,
            color = Ink.faint,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Door(
            title = "Try it free",
            badge = "NO ACCOUNT",
            detail = "Free models through Kilo Gateway, with no sign-up and no key. About 200 requests an hour, " +
                "which is plenty to try Droid.",
            onClick = { onOpen(Inside.Free) },
        )
        Door(
            title = "Use my computer's model",
            detail = "Ollama or LM Studio on the same Wi-Fi. Private, free, and no limits.",
            onClick = { onOpen(Inside.Computer) },
        )
        Door(
            title = "Sign in or use a key",
            detail = "OpenRouter, Google Gemini, NVIDIA and others, or any OpenAI-compatible address.",
            onClick = { onOpen(Inside.Keys) },
        )
    }
    QuietButton(text = "Scan a setup code", onClick = onScanCode, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun Free() {
    Prose(
        "Droid will use Kilo Gateway's free models. It picks the best free model that can see images and " +
            "use tools, and you can change it later.",
    )
    Prose(
        "The free tier allows about 200 requests an hour from your connection. One task takes 10 to 30, " +
            "so expect around 10 tasks an hour.",
    )
    Text(
        text = "Free requests run on NVIDIA's trial service, which keeps logs. Do not use the free models on " +
            "screens with private information, such as banking or health apps. Use your own computer or a " +
            "key for those.",
        style = Type.body,
        color = Ink.amber,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun Computer(
    servers: List<Lan.Server>?,
    scanning: Boolean,
    onWifi: Boolean,
    onScan: () -> Unit,
    onServer: (Lan.Server, String) -> Unit,
    onLink: (String) -> Unit,
    onType: () -> Unit,
) {
    LaunchedEffect(Unit) { if (servers == null && !scanning) onScan() }
    var picking by remember { mutableStateOf<Lan.Server?>(null) }
    when {
        scanning -> Prose("Looking for Ollama and LM Studio on your Wi-Fi. This takes a few seconds.")
        !onWifi && servers.isNullOrEmpty() -> Prose(
            "This phone is not on Wi-Fi. Connect it to the same Wi-Fi as your computer, then scan again.",
        )
        servers.isNullOrEmpty() -> NothingFound(onLink)
        else -> {
            Prose("Tap the model to use. Pick one that can use tools; bigger models follow instructions better.")
            servers.forEach { server ->
                SectionLabel(server.name.uppercase(), Modifier.padding(top = 20.dp, bottom = 8.dp))
                GroupCard {
                    if (server.models.isEmpty()) {
                        Text(
                            text = "It has no models yet. Download one on the computer, then scan again.",
                            style = Type.secondary,
                            color = Ink.dim,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                    server.models.take(SHOWN_MODELS).forEachIndexed { i, model ->
                        SettingRow(label = Models.pretty(model), value = "", onClick = { onServer(server, model) })
                        if (i < server.models.size.coerceAtMost(SHOWN_MODELS) - 1) RowDivider()
                    }
                    if (server.models.size > SHOWN_MODELS) {
                        RowDivider()
                        SettingRow(
                            label = "All ${server.models.size} models",
                            value = "",
                            onClick = { picking = server },
                        )
                    }
                }
            }
        }
    }
    if (!scanning) {
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuietButton(text = "Scan again", onClick = onScan, modifier = Modifier.weight(1f), color = Ink.accent)
            QuietButton(text = "Type the address", onClick = onType, modifier = Modifier.weight(1f))
        }
    }
    picking?.let { server ->
        PickerSheet(
            label = server.name.uppercase(),
            options = server.models.map { Option(id = it, title = Models.pretty(it), detail = it) },
            chosenId = null,
            onPick = { picking = null; onServer(server, it.id) },
            onDismiss = { picking = null },
        )
    }
}

/** What to change on the computer, because both servers only listen to the computer itself out of the box. */
@Composable
private fun NothingFound(onLink: (String) -> Unit) {
    Prose(
        "Nothing answered. Ollama and LM Studio only listen to their own computer until you let the " +
            "network in. Do this on the computer, then scan again.",
    )
    SectionLabel("OLLAMA", Modifier.padding(top = 20.dp, bottom = 8.dp))
    CodeBlock(text = "OLLAMA_HOST=0.0.0.0 OLLAMA_CONTEXT_LENGTH=32768 ollama serve", onCopy = null)
    Prose(
        "Or in the Ollama app's settings, turn on Expose Ollama to the network. The longer context matters: " +
            "Droid's instructions alone fill Ollama's default.",
    )
    SectionLabel("LM STUDIO", Modifier.padding(top = 20.dp, bottom = 8.dp))
    Prose(
        "Open the Developer tab, start the server, and in its settings turn on Serve on Local Network. " +
            "Or run this in a terminal:",
        Modifier.padding(top = 0.dp),
    )
    CodeBlock(text = "lms server start --bind 0.0.0.0", onCopy = null, modifier = Modifier.padding(top = 8.dp))
    QuietButton(
        text = "LM Studio's guide",
        onClick = { onLink("https://lmstudio.ai/docs/developer/core/server/serve-on-network") },
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun Keys(
    providers: List<Models.Provider>,
    keys: Set<String>,
    onOpen: (Inside) -> Unit,
    onSignIn: () -> Unit,
    busy: String?,
    problem: String?,
) {
    var more by remember { mutableStateOf(false) }
    val keyed = providers.filter { it.needsKey && it.keyVars.isNotEmpty() && !it.custom }
    val short = Models.SHORTLIST.mapNotNull { id -> keyed.firstOrNull { it.id == id } }
    Door(
        title = busy ?: "Sign in with OpenRouter",
        detail = "Opens OpenRouter in the browser and brings a key back, with nothing to copy. " +
            "It has free models and paid ones.",
        onClick = onSignIn,
        modifier = Modifier.padding(top = 12.dp),
    )
    if (problem != null) {
        Text(text = problem, style = Type.secondary, color = Ink.red, modifier = Modifier.padding(top = 10.dp))
    }
    SectionLabel("PASTE A KEY", Modifier.padding(top = 24.dp, bottom = 8.dp))
    GroupCard {
        short.forEachIndexed { i, provider ->
            SettingRow(
                label = provider.name,
                value = when {
                    provider.keyVars.any { it in keys } -> "Key saved"
                    provider.id in FREE_TIER -> "Free tier"
                    else -> ""
                },
                onClick = { onOpen(Inside.Key(provider)) },
            )
            if (i < short.lastIndex) RowDivider()
        }
    }
    GroupCard(Modifier.padding(top = 10.dp)) {
        SettingRow(label = "More providers", value = "${keyed.size}", onClick = { more = true })
        RowDivider()
        SettingRow(label = "Any OpenAI-compatible address", value = "", onClick = { onOpen(Inside.Custom("https://")) })
    }
    if (more) {
        PickerSheet(
            label = "PROVIDER",
            options = keyed.map { Option(id = it.id, title = it.name, detail = it.keyVars.firstOrNull()) },
            chosenId = null,
            onPick = { picked ->
                more = false
                keyed.firstOrNull { it.id == picked.id }?.let { onOpen(Inside.Key(it)) }
            },
            onDismiss = { more = false },
        )
    }
}

@Composable
private fun KeyEntry(
    provider: Models.Provider,
    busy: String?,
    problem: String?,
    onKey: (Models.Provider, String, Boolean) -> Unit,
    onLink: (String) -> Unit,
) {
    var key by remember(provider.id) { mutableStateOf("") }
    Prose("Paste your ${provider.name} key. It stays on this phone, in Droid's own files, and is only sent to ${provider.name}.")
    KEY_PAGES[provider.id]?.let { page ->
        QuietButton(text = "Get a key from ${provider.name}", onClick = { onLink(page) }, color = Ink.accent)
    }
    Field(
        value = key,
        onValueChange = { key = it.trim() },
        hint = "Paste the key here",
        secret = true,
        onDone = { if (key.isNotEmpty() && busy == null) onKey(provider, key, true) },
        modifier = Modifier.padding(top = 8.dp),
    )
    if (problem != null) {
        Text(text = problem, style = Type.secondary, color = Ink.red, modifier = Modifier.padding(top = 10.dp))
    }
    Spacer(Modifier.height(16.dp))
    PrimaryButton(
        text = busy ?: "Check and use it",
        enabled = key.isNotEmpty() && busy == null,
        onClick = { onKey(provider, key, true) },
    )
    if (problem != null && busy == null) QuietButton(text = "Use it anyway", onClick = { onKey(provider, key, false) })
}

@Composable
private fun Custom(start: String, busy: String?, problem: String?, onCustom: (String, String, String, Boolean) -> Unit) {
    var url by remember(start) { mutableStateOf(start) }
    var model by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    val allowed = Lan.allowedUrl(url)
    Prose(
        "Any server that speaks the OpenAI chat API: a router, a company gateway, or a model on your own " +
            "network. The address usually ends in /v1.",
    )
    SectionLabel("ADDRESS", Modifier.padding(top = 20.dp, bottom = 8.dp))
    Field(value = url, onValueChange = { url = it.trim() }, hint = "https://example.com/v1", uri = true)
    if (url.length > "https://".length && !allowed) {
        Text(
            text = "Use an https address, or plain http only to a computer on your own network, such as 192.168.1.20.",
            style = Type.secondary,
            color = Ink.amber,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    SectionLabel("MODEL", Modifier.padding(top = 20.dp, bottom = 8.dp))
    Field(value = model, onValueChange = { model = it.trim() }, hint = "The model's id, as the server lists it")
    SectionLabel("KEY, IF IT NEEDS ONE", Modifier.padding(top = 20.dp, bottom = 8.dp))
    Field(value = key, onValueChange = { key = it.trim() }, hint = Models.SHARED_KEY, secret = true)
    if (problem != null) {
        Text(text = problem, style = Type.secondary, color = Ink.red, modifier = Modifier.padding(top = 10.dp))
    }
    Spacer(Modifier.height(16.dp))
    val ready = allowed && model.isNotEmpty() && busy == null
    PrimaryButton(text = busy ?: "Check and use it", enabled = ready, onClick = { onCustom(url, model, key, true) })
    if (problem != null && ready) QuietButton(text = "Use it anyway", onClick = { onCustom(url, model, key, false) })
}

@Composable
private fun Talk(channel: Channel, onOpen: (Inside) -> Unit, onChannel: (Channel, Map<String, String>) -> Unit) {
    Prose("You can change this later in settings.")
    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Door(
            title = "In this app",
            badge = if (channel == Channel.APP) "NOW" else "SIMPLEST",
            detail = "A chat inside Droid. Nothing else to set up.",
            onClick = { onChannel(Channel.APP, emptyMap()) },
        )
        Door(
            title = "Telegram",
            badge = if (channel == Channel.TELEGRAM) "NOW" else null,
            detail = "Message the agent from any device through your own Telegram bot.",
            onClick = { onOpen(Inside.Telegram) },
        )
        Door(
            title = "iMessage or WhatsApp",
            badge = if (channel == Channel.PHOTON) "NOW" else null,
            detail = "Text the agent like a person, through a free Photon project.",
            onClick = { onOpen(Inside.Photon) },
        )
        Door(
            title = "Nowhere, just let my agents in",
            badge = if (channel == Channel.NONE) "NOW" else null,
            detail = "No agent runs on the phone. Claude Code, Codex, Cursor and others drive it over MCP or ACP.",
            onClick = { onChannel(Channel.NONE, emptyMap()) },
        )
    }
}

@Composable
private fun Telegram(onChannel: (Channel, Map<String, String>) -> Unit, onLink: (String) -> Unit) {
    var token by remember { mutableStateOf("") }
    var users by remember { mutableStateOf("") }
    val valid = TELEGRAM_TOKEN.matches(token)
    Prose(
        "In Telegram, message @BotFather, send /newbot, and paste the token it gives you here.",
    )
    QuietButton(text = "Open @BotFather", onClick = { onLink("https://t.me/BotFather") }, color = Ink.accent)
    SectionLabel("BOT TOKEN", Modifier.padding(top = 12.dp, bottom = 8.dp))
    Field(value = token, onValueChange = { token = it.trim() }, hint = "123456789:AA...", secret = true)
    SectionLabel("YOUR TELEGRAM ID", Modifier.padding(top = 20.dp, bottom = 8.dp))
    Field(value = users, onValueChange = { users = it.trim() }, hint = "Leave empty if you do not know it")
    Prose(
        "Once Droid starts, message your bot. Until your id is here it answers nobody, except to tell you " +
            "your id. Paste that into Allowed people in settings.",
    )
    Spacer(Modifier.height(16.dp))
    PrimaryButton(
        text = "Use Telegram",
        enabled = valid,
        onClick = { onChannel(Channel.TELEGRAM, mapOf(Env.TELEGRAM_TOKEN to token, Env.REMOTE_USERS to users)) },
    )
}

@Composable
private fun Photon(onChannel: (Channel, Map<String, String>) -> Unit, onLink: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    var project by remember { mutableStateOf("") }
    var users by remember { mutableStateOf("") }
    Prose(
        "Photon gives the agent its own iMessage or WhatsApp line. Make a free project at photon.codes, " +
            "then paste its project key and project id here.",
    )
    QuietButton(text = "Open photon.codes", onClick = { onLink("https://photon.codes") }, color = Ink.accent)
    SectionLabel("PROJECT KEY", Modifier.padding(top = 12.dp, bottom = 8.dp))
    Field(value = key, onValueChange = { key = it.trim() }, hint = "pho_sk_...", secret = true)
    SectionLabel("PROJECT ID", Modifier.padding(top = 20.dp, bottom = 8.dp))
    Field(value = project, onValueChange = { project = it.trim() }, hint = "pho_prj_...")
    SectionLabel("WHO MAY TEXT IT", Modifier.padding(top = 20.dp, bottom = 8.dp))
    Field(value = users, onValueChange = { users = it.trim() }, hint = "+447700900123, you@example.com")
    Prose("Phone numbers or emails, separated by commas. Anyone else is ignored.")
    Spacer(Modifier.height(16.dp))
    PrimaryButton(
        text = "Use Photon",
        enabled = key.isNotEmpty() && project.isNotEmpty(),
        onClick = {
            onChannel(
                Channel.PHOTON,
                mapOf(Env.PHOTON_KEY to key, Env.PHOTON_PROJECT to project, Env.REMOTE_USERS to users),
            )
        },
    )
}

@Composable
private fun Permissions(
    readiness: Readiness,
    onGrant: (GrantId) -> Unit,
    onAppInfo: () -> Unit,
    onPosting: () -> Unit,
    onBattery: () -> Unit,
    onEverything: () -> Unit,
) {
    Prose("Only the first is needed. Each opens the system page for it; come back here when it is on.")
    GroupCard(Modifier.padding(top = 20.dp)) {
        Need(
            label = "Screen",
            detail = "Lets the agent see the screen, tap and scroll. Needed.",
            on = readiness.screen,
            onClick = { onGrant(GrantId.SCREEN) },
        )
    }
    if (!readiness.screen && readiness.restricted) RestrictedHelp(onAppInfo)
    SectionLabel("OPTIONAL", Modifier.padding(top = 24.dp, bottom = 8.dp))
    GroupCard {
        Need(
            label = "Alerts",
            detail = "The agent's questions and replies, while Droid is in the background.",
            on = readiness.posting,
            onClick = onPosting,
        )
        RowDivider()
        Need(
            label = "Keep running",
            detail = "Stops the battery saver from pausing the agent when the screen is off.",
            on = readiness.battery,
            onClick = onBattery,
        )
        RowDivider()
        Need(
            label = "Keyboard",
            detail = "Lets the agent type. Droid's keyboard has no keys, so switch back to yours to type yourself.",
            on = readiness.keyboard,
            onClick = { onGrant(GrantId.KEYBOARD) },
        )
        RowDivider()
        Need(
            label = "Notifications",
            detail = "Lets the agent read your notifications, to react to messages and alerts.",
            on = readiness.listener,
            onClick = { onGrant(GrantId.NOTIFICATIONS) },
        )
        RowDivider()
        Need(
            label = "Calls, messages and more",
            detail = if (readiness.ungranted == 0) {
                "Contacts, calendar, location, calls and texts are all given."
            } else {
                "Contacts, calendar, location, calls and texts, for asking the agent to use them directly. " +
                    "The phone asks about each one."
            },
            on = readiness.ungranted == 0,
            onClick = onEverything,
        )
    }
}

/** The Android 13 block on sideloaded apps, and the way round it. */
@Composable
private fun RestrictedHelp(onAppInfo: () -> Unit) {
    GroupCard(Modifier.padding(top = 10.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = "If the switch is greyed out, Android is blocking it because Droid was installed from a file.",
                style = Type.body,
                color = Ink.amber,
            )
            Text(
                text = "Open App info, tap the three-dot menu at the top right, and choose Allow restricted " +
                    "settings. Confirm, come back here, and tap Screen again.",
                style = Type.secondary,
                color = Ink.dim,
                modifier = Modifier.padding(top = 6.dp),
            )
            QuietButton(text = "Open App info", onClick = onAppInfo, color = Ink.accent)
        }
    }
}

@Composable
private fun Need(label: String, detail: String, on: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressable(onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(if (on) Ink.live else Ink.accent)
        Column(Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = Ink.text)
            Text(text = detail, style = Type.secondary, color = Ink.dim, modifier = Modifier.padding(top = 2.dp))
        }
        Text(
            text = if (on) "On" else "Off",
            style = MaterialTheme.typography.labelMedium,
            color = if (on) Ink.dim else Ink.accent,
        )
        Chevron()
    }
}

@Composable
private fun Done(brain: String?, channel: Channel, readiness: Readiness) {
    GroupCard(Modifier.padding(top = 20.dp)) {
        ValueRow("Brain") { Plain(brain ?: "Not chosen") }
        RowDivider()
        ValueRow("Talk") { Plain(where(channel)) }
        RowDivider()
        ValueRow("Screen") { Plain(if (readiness.screen) "On" else "Off") }
    }
    Prose(
        when {
            !readiness.screen -> "The screen switch is off, so the agent can talk but cannot use the phone yet. " +
                "Turn on Screen from the home screen when you are ready."
            channel == Channel.NONE -> "No agent runs on the phone. Open Connect a computer in settings to " +
                "let Claude Code, Codex, Cursor or Zed in."
            channel == Channel.APP -> "Ask it for something, such as \"turn on dark mode\" or \"what is on my " +
                "calendar tomorrow?\""
            else -> "Droid starts and connects to ${channel.label}. Message it there, or use the chat here."
        },
    )
    Prose("Settings has Set up again, if you want to change any of this.")
}

@Composable
private fun Plain(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = Ink.text,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.End,
        modifier = Modifier.padding(start = 16.dp),
    )
}

/** The channel as its door on the talk step names it. */
private fun where(channel: Channel): String = when (channel) {
    Channel.APP -> "In this app"
    Channel.TELEGRAM -> "Telegram"
    Channel.PHOTON -> "iMessage or WhatsApp"
    Channel.NONE -> "Only other agents"
}

private const val SHOWN_MODELS = 6
private val TELEGRAM_TOKEN = Regex("[0-9]{5,15}:[A-Za-z0-9_-]{20,80}")
/** Providers with a free tier worth naming in the list. */
private val FREE_TIER = setOf("openrouter", "google_gemini", "nvidia_nim", "mistral")
/** Where each shortlisted provider hands out keys, so nobody has to search for the page. */
private val KEY_PAGES = mapOf(
    "openrouter" to "https://openrouter.ai/keys",
    "google_gemini" to "https://aistudio.google.com/apikey",
    "nvidia_nim" to "https://build.nvidia.com",
    "mistral" to "https://console.mistral.ai/api-keys",
    "deepseek" to "https://platform.deepseek.com/api_keys",
    "anthropic" to "https://console.anthropic.com/settings/keys",
    "openai" to "https://platform.openai.com/api-keys",
    "moonshot" to "https://platform.moonshot.ai",
    "zai" to "https://z.ai/manage-apikey/apikey-list",
)
