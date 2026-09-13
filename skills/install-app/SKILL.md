---
name: install-app
description: Install an app from the Play Store on this phone and open it afterwards. Use whenever a task needs an app that is not installed yet, and before tapping anything in the store, which is full of sponsored rows that look like the result.
---

# Installing an app

Check first. `asterctl apps <name>` lists what is installed, and
`asterctl open <name>` launches it. Neither costs a store round trip.

## Go straight to the listing

Play Store search is the slow route and it lies: the first rows are sponsored
apps that are not what you asked for, and typing into a field that already
holds text produces `TextNowTextNow` rather than a new query. Open the exact
listing instead, by package:

```sh
asterctl url 'https://play.google.com/store/apps/details?id=com.whatsapp'
```

`asterctl install <app>` also opens a store page and is fine when you do not
know the package.

## Press the right Install

The listing has more than one Install button, because the sponsored block
below the fold has its own. Never take the first match from
`asterctl find Install`. Read the map and take the button highest on the page,
the one inside the block that carries the app's own name and developer.

## Wait for the right word

The button reads `Install`, then `Open`. It never reads `Installed`, so
`wait Installed` always burns its timeout. A big app takes longer than a wait
is worth: set `asterctl later 3m check the install finished and open it` and
end the turn rather than holding the line.

## Open it yourself

Tapping `Open` in the store reports `changed: +0 -0` and does nothing often
enough that it is not worth trying. Launch it directly once the install is
done:

```sh
asterctl open WhatsApp
```

That returns the new package and its map in the same call, so the first screen
of the app is already in front of you.

## When it will not install

`This item isn't available in your country` means the app is region locked and
no amount of retrying changes it. This phone is in Nigeria, so North America
only services (TextNow and similar) are dead ends. Say so and offer what the
phone can actually do instead.
