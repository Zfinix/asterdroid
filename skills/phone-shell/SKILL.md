---
name: phone-shell
description: What the shell on this phone can and cannot do. Use before reaching for a unix tool: git, adb, ps/kill, netstat, ip, force-stop and the socket tables are all missing or denied here, and each one has a working substitute.
---

# The shell on this phone

The shell is an app's shell, not a computer's. It runs as the app's own uid
with no root, no busybox beyond what Android ships, and no package manager.
Every limit below was found the hard way; take the substitute rather than
confirming the limit again.

## What is not there

| reach for | what happens | take instead |
| --- | --- | --- |
| `git` | `Permission denied (os error 13)`, so `aster skills add <slug>` fails too | `curl` the file from `raw.githubusercontent.com` |
| `adb` | `inaccessible or not found`; adb runs on the other machine, not here | drive the screen with `asterctl` |
| `crond`, `crontab` | not found, so `aster cron` cannot install anything | `asterctl later <delay> <what to do next>` |
| `am force-stop` | `SecurityException`: FORCE_STOP_PACKAGES is not yours | `asterctl restart <app>` |
| `input keyevent` | denied, no INJECT_EVENTS | `asterctl key ...`, including `key lock` |
| `netstat`, `ss`, `/proc/net/tcp` | empty output or `Permission denied`, even with a server listening | probe the port with `curl` |
| `ip addr`, `ifconfig` | `Permission denied` on the netlink socket | read the address off a Settings screen |
| `ps -ef` | only Aster's own handful of processes | `pgrep -f` for those, nothing else |
| PIL, numpy, node | not installed and not installable | `aster python` stdlib: zlib and struct write a PNG by hand |

`curl` works and is the most useful tool here. A port check is
`curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://127.0.0.1:<port>/`,
where `000` or exit 7 means nothing is listening.

## Two traps that cost a whole call

`pkill -f <pattern>` matches the agent's own shell command line and kills the
shell running it, which takes the rest of the chain with it, including anything
you had just started. Use `pgrep -f 'pat[t]ern'` with the bracket trick, then
`kill` the pids it prints.

A `sh -c` call that ends killed takes its background children with it. Start a
long-running process so the call still exits 0, and read the log in the same
call to prove it came up:

```sh
(nohup aster python server.py >> $TMPDIR/server.log 2>&1 &); sleep 2; cat $TMPDIR/server.log
```

## Where files go

`$TMPDIR` is the app's cache directory and it persists between calls. Anything
handed to another tool, such as an image for `telegram/send_photo`, needs its
absolute path from there. There is no `/tmp`, and `/sdcard` is not writable by
this app except under its own `Android/data` directory.
