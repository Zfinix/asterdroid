---
name: python
description: Run Python 3 on this phone with `aster python`. Use whenever a task needs real computation, parsing, or data handling: solving a puzzle, transforming text pulled off the screen, doing arithmetic over many values, or anything you would otherwise reason through step by step.
---

# Python on this phone

There is no system python, node, or bash here, and no `/tmp`. Python 3 is
built into the agent binary with the standard library included:

```sh
aster python script.py arg1 arg2   # a file, with sys.argv[1:] = ["arg1", "arg2"]
aster python -c 'print(2 ** 64)'   # one-liner; everything after it is sys.argv[1:]
echo 'print(1)' | aster python -   # from stdin
```

Write scripts under `$TMPDIR`, which is the app's cache directory. `/tmp` does
not exist and the shell is `sh`, not bash. A module next to the script imports
by name as usual.

## What is there

The standard library: `json`, `re`, `itertools`, `collections`, `math`,
`datetime`, `csv`, `base64`, `hashlib`, `statistics`, `os`, `subprocess`,
`socket`, `urllib`. No pip and no third-party packages. A script can drive
the phone itself with `subprocess.run(["asterctl", "map"], capture_output=True)`,
which is how to loop over many taps without a round trip for each.

## When to reach for it

Compute, then act. Read a board or a table off the screen once, solve or
transform it in a script, and only then go back to tapping with the answer in
hand. Ten taps driven by a script's output beat fifty taps driven by guessing.
A traceback means the script is wrong, not the phone: fix the script and rerun
it rather than falling back to doing the work by hand.
