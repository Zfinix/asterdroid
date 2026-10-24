# droid.withaster.dev

The Droid website: a static site with no build step, served by Cloudflare Workers static assets.

| Path | What it is |
| --- | --- |
| `index.html` | The landing page, with `styles.css`, `ios.css`, `sorbet.css` and `app.js` |
| `s/index.html` | Where a setup link (`/s#1.<payload>`) lands when Droid is not there to open it |
| `404.html` | Served for any path that does not exist |
| `.well-known/assetlinks.json` | Lets Android verify the `https://droid.withaster.dev/s` app link for `dev.aster.probe` |
| `robots.txt` | Keeps setup links out of search |
| `img/`, `mascot/`, `logos/` | Only the assets the pages use |

## Run it locally

```sh
cd site
python3 -m http.server 8080
```

Then open http://localhost:8080. Every path in the pages works from the site root, so serve this folder, not the repo.

## Setup links

A setup link carries the whole setup in the URL fragment, which a browser never sends to a server. On a phone with Droid installed, Android opens the app straight away, because `assetlinks.json` names the release signing certificate. Anywhere else the browser loads `s/index.html`, which only checks that a fragment is there and tells the person to open the link on their phone. It never decodes or sends the payload.

The fingerprint in `assetlinks.json` is the SHA-256 of the release certificate. If the release key ever changes, read the new one with

```sh
keytool -list -v -keystore aster-release.jks -alias <alias> | grep SHA256
```

and replace it. Debug builds are signed with a different key, so the link opens them only after you pick Droid by hand.

## Deploy

`wrangler.jsonc` serves this folder as static assets under the Worker name `asterdroid-web`, the one already behind `droid.withaster.dev`, so a deploy replaces the live site. The custom domain route and the account are set in the file, and observability is on. `.assetsignore` keeps the config and this README out of the upload.

```sh
cd site
npx wrangler deploy
```

With `html_handling` set to `auto-trailing-slash`, `/s` redirects to `/s/` and the browser keeps the fragment.
