# Setup

The short path is in the [README](../README.md#quick-start). This page is the same setup in detail, plus configuration and troubleshooting.

## Connect your Telegram bot

### 1. Create a bot with BotFather

Open [@BotFather](https://t.me/BotFather) in Telegram and send `/newbot`. Follow the prompts to name your bot and choose its username. BotFather returns a bot token. Copy it and keep the link to your new bot handy. See [Telegram's bot setup guide](https://core.telegram.org/bots/tutorial#obtain-your-bot-token) for details.

### 2. Add the token to asterdroid

On the Android phone, open asterdroid and tap the settings icon in the top right.

- Paste the full bot token into **Telegram token**.
- Select your model provider and enter its API key. The settings sheet shows the env variable each provider expects. The full list is in [PROVIDERS.md](PROVIDERS.md).
- Leave **Allowed ids** empty for now. The next step gets your numeric Telegram user ID.

Save the settings. Changes are written to the agent's `.env`; saving a key restarts the agent.

### 3. Start the agent and message your bot

Press the start control on asterdroid's home screen. Wait for the notification to say **Connected to Telegram**.

In Telegram, open the bot you just created and send `hello`. Send this to your own bot, not BotFather.

With no allowed users configured, it replies:

```txt
This bot isn't set up yet. Your user id is 8675309. Restart it with --user 8675309 to allow it.
```

Copy the user ID from your reply. The number above is an example.

### 4. Allow your Telegram account

Return to asterdroid's settings, paste that numeric ID into **Allowed ids**, and save. Use your user ID here, not your Telegram username or bot token. To allow multiple accounts, separate their IDs with commas.

The reply mentions `--user`, but on Android you configure this through **Allowed ids**. Once configured, the bot ignores accounts outside that list.

### 5. Send a test instruction

Return to your bot's Telegram chat and send:

```txt
Open settings and turn on Do Not Disturb.
```

The agent should respond and carry out the action on the Android phone. Use `/help` for chat commands, or `/mirror` to view the phone in a browser.

If the bot does not reply, check that asterdroid says **Connected to Telegram**, that you opened the bot whose token you entered, and that **Allowed ids** contains the ID from your own reply.

## Optional: configure from your computer

You can push a `.env` instead of entering values in the app. Replace the placeholders with your bot token, provider key and numeric Telegram user ID:

```sh
cat > .env <<'EOF'
ASTER_TELEGRAM_TOKEN=YOUR_BOT_TOKEN
<PROVIDER>_API_KEY=YOUR_PROVIDER_KEY
ASTER_REMOTE_USERS=YOUR_TELEGRAM_USER_ID
EOF
adb push .env /sdcard/Android/data/dev.aster.probe/files/.env
```

The pushed values are merged with the phone's existing configuration. If you do not know your user ID yet, use steps 3 and 4 above to obtain and allow it.

## Configuration

The agent uses these environment variables:

| variable | set by | what it is |
| --- | --- | --- |
| `ASTER_TELEGRAM_TOKEN` | the settings sheet, or a pushed `.env` | the bot token; without it the bridge exits |
| `ASTER_REMOTE_USERS` | the settings sheet, or a pushed `.env` | comma-separated Telegram ids allowed to drive it |
| `<PROVIDER>_API_KEY` | the settings sheet, or a pushed `.env` | whichever key the chosen provider wants |
| `ASTER_BASE_URL`, `ASTER_MODEL`, `ASTER_EFFORT` | the app's pickers | the provider, model and effort for the next start |
| `ASTER_COMPACT_BUDGET` | the service (60000) | limits context growth from repeated screen maps |
| `HOME`, `TMPDIR`, `PATH` | the service | the app's private files, its cache, and `files/bin` |

The settings sheet and pushed files update the same `files/.env`. Each update preserves unrelated keys. Imports overwrite only the names present in the pushed file.

`dotenvy` preserves variables already set in the process environment, so the app's provider and model selections take precedence over `.env`.

## Troubleshooting

| symptom | what it is | what to do |
| --- | --- | --- |
| the notification never says "Connected" | no token, or no network | check `.env` landed (debug builds): `adb shell run-as dev.aster.probe cat files/.env` |
| the bot answers strangers with an id | no allowed ids yet | paste the id it printed into Allowed ids under the settings icon |
| "This build has no agent" | `libaster.so` is missing from the APK | rerun `./build-agent.sh`, rebuild, reinstall |
| every verb says it cannot reach the service | the accessibility service is off | rerun `./grant.sh`, or turn Aster on in Accessibility settings |
| `type` reports posted and nothing arrives | the field owns its own input (a terminal) | read the screen after typing; there is no way around it from here |
| `serve is not a verb` | a stale `asterctl` | `./build-client.sh`, reinstall, then open the app once so the bin symlinks refresh |
| `/mirror` says the port is held | something else is listening on 7070 | stop it; the mirror will not kill a process it does not own |
| the viewer says capture was not allowed | a PIN or pattern keyguard | unlock the phone once; that is a lock that requires manual input |
| nothing works after a reboot | the agent is not started at boot yet | open the app once |
| the agent tapped nothing and said the screen is locked | the keyguard is up | taps do not reach apps behind it; unlock first |

Logs are under the `ASTEREYES`, `ASTERAGENT`, `ASTERPROBE`, `ASTERINSTALL`, `ASTERWAKE` and `aster-mirror` tags.