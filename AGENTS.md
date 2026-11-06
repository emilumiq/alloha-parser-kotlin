# Alloha parser — engineering guide for agents

Android reference implementation of an **Alloha** (api.alloha.tv) stream extractor.
Alloha never returns a playable URL directly. It returns a **live, signed HLS session** that must be
captured from a real browser page, kept alive, and proxied. Everything in this repo exists to do that
reliably. This document is the porting manual: it describes the protocol, the state machine, the
invariants that were measured on devices, and the exact order in which to rebuild it in any language
or UI framework.

Read §3 (invariants) before changing anything. Read §10 for a porting checklist.

---

## 1. TL;DR pipeline

```
api.alloha.tv?token&kp                     catalog: title / seasons / episodes / translations(iframe)
        │
        ▼
iframe URL  ──►  hidden WebView + wrapper HTML + injected JS
                    │  hooks XHR / fetch / WebSocket of the player page
                    │  captures: bnsi JSON, anti-bot headers, signed master.m3u8 URL
                    ▼
             Session state (headers + master URL + TTL + generation)
                    │
                    ▼
             Loopback HTTP proxy (127.0.0.1:random, per-session token)
                rewrites every playlist URL to itself, injects session headers,
                caches/prefetches segments, recovers 403s, rotates the session
                    │
                    ▼
             Media3 / ExoPlayer plays http://127.0.0.1:PORT/master.m3u8?token=…
```

The player is headless. Nothing from the WebView is shown; it exists only to make the site produce
its own signed requests, which are observed and replayed by the proxy.

---

## 2. Why a WebView is required

- `bnsi` is fetched by the page after it computes request signatures (`authorizations`,
  `accepts-controls`) that are not derivable offline.
- The CDN decrypts the path token using those same headers; a hand-built request gets
  `403 X-VD=token_decrypt` or `client_blocked`.
- The page's own `master.m3u8` request carries the freshest path token. It must be observed, not
  guessed.

No pure-HTTP extractor can work. Any port must have: an embeddable browser with JS injection and a
JS↔native bridge, a local HTTP server, an HLS player.

---

## 3. Invariants (measured; do not "optimize" away)

1. **Wrapper must be same-origin with the iframe.** Load the wrapper with
   `loadDataWithBaseURL(baseUrl = iframe scheme+host)`. Otherwise `iframe.contentWindow.document`
   is inaccessible and no hook can be installed.
2. **Install hooks by polling `contentWindow` (~20 ms), not on `iframe.onload`.** By `load` the page
   may have already issued `/bnsi/`. Reference measurements: the page loaded 46/46 times, but bnsi
   was captured only 8 times when hooks were installed on `load`.
3. **Guard hook installation on the wrapped function (`open.__alloha`), not on a window property.**
   A window expando is dropped; polling then wraps hooks recursively (measured: 526 nested wrappers,
   player killed).
4. **Never block the page's own `master.m3u8` request.** The token is not single-use; the proxy
   re-fetches the same URL. Blocking it leaves the page player without media, its WebSocket dies,
   and `config_update` never arrives.
5. **Bind the proxy to the literal `127.0.0.1`.** `getLoopbackAddress()` may return `::1` while all
   URLs point at `127.0.0.1` → `ConnectException` on every request.
6. **Never answer `404` for network failures.** Media3 treats `400/401/403/404/410` as fatal.
   `404` is only for malformed requests; all network problems must surface as retryable `503`.
7. **Do not forward `borth`.** It is a per-request nonce; replaying it yields intermittent
   `403 token_decrypt`. Also never forward `host`, `connection`, `content-length`,
   `transfer-encoding`, `accept-encoding`, `cookie`, `x-requested-with`, `content-type`.
8. **Add the headers a real Chrome adds but the page never sets explicitly:** `Accept-Language` and
   the `sec-ch-ua*` client hints, derived from the UA you present. Without them the CDN sees a
   desktop-Chrome UA with no hints and answers `403 client_blocked`, even though the page's own
   request to the same master succeeds.
9. **Keep the browser fingerprint stable** for the whole session (and ideally across sessions).
   Re-randomising the UA per open looks like a bot farm from one IP.
10. **A WebView reload re-emits session data in stages.** During a rotation, write new headers,
    master URL and edge hash into a *staged* slot and swap atomically; the old token keeps serving
    until the commit.
11. **`config_update` often never arrives.** The page WebSocket usually closes ~2.5 s after opening
    (`code=1005`). If the session has never seen `config_update`, rotations must commit on
    `sources + master` alone; otherwise every rotation pays the 8 s commit timeout.
12. **`rewriteToCurrentPath` is cheap and must be tried before escalating.** After a rotation, a
    segment with the old path is valid under the new path prefix.
13. **Preserve the segment cache and in-flight fetches on rotation.** They were authorized by the
    previous token and are still valid. Only the HTTP transport (client + pool) is rebuilt.
14. **For unrecoverable `-a1.ts` / `-a2.ts` audio segments return a 188-byte empty TS packet**, not
    an error — the reference parser does the same and playback continues.
15. **Do not call `selectAudioTrack` before playback starts** (equivalent of setting the master to a
    raw bnsi URL). It overwrites the correctly-signed master and causes `403 token_decrypt` on some
    CDNs.

---

## 4. Component map

| File | Responsibility |
|------|----------------|
| `api/AllohaApiClient.kt` | Catalog HTTP call (cancellable OkHttp enqueue → coroutine). |
| `api/AllohaCatalogParser.kt` | Pure parsing of the catalog JSON: seasons/episodes/translations, numeric sort, dedupe by iframe. |
| `parser/AllohaWrapperScript.kt` | HTML/JS shell injected into the WebView. All page observation lives here. |
| `parser/AllohaWebViewPool.kt` | One warm WebView, ad-host blocklist, identity reset (cookies + DOM storage). |
| `parser/AllohaSessionBridge.kt` | `@JavascriptInterface` bridge: parse `bnsi`/headers off the main thread, marshal to main. |
| `parser/AllohaSessionOpener.kt` | Orchestrator: load wrapper, install bridge, layered timeouts, reloads, host-change handling, delivers a `Ready(session)`. |
| `parser/AllohaSession.kt` | Live session state: headers, master URL, TTL, generation, staged rotation, audio/quality selection, owns the proxy. |
| `parser/AllohaSourceParser.kt` | Pure `bnsi` parser: audio tracks, quality ladder, subtitles. |
| `parser/AllohaAssPositionFix.kt` | Rewrites ASS/SSA `MarginV/L/R` into `\pos()` because Media3 ignores margins. |
| `proxy/AllohaProxyUrls.kt` | Pure URL helpers: query parsing, content types, stale-path rewrite, ASS detection. |
| `proxy/AllohaStreamProxy.kt` | Loopback HLS proxy: token gate, playlist rewriting, cache, prefetch, recovery ladder. |
| `player/AllohaPlayerController.kt` | ExoPlayer wrapper: media item + subtitle configs, audio/subtitle overrides, stream switching. |
| `player/PlaybackStore.kt` + `PlaybackSnapshotCodec.kt` | Persisted resume point and cached catalog. |
| `ui/AllohaViewModel.kt` | State machine: catalog, session open, prefetch, background/restore, error recovery, snapshots. |
| `ui/*Section.kt`, `ui/components/*` | Compose Material 3 UI (presentation only; no parsing). |

---

## 5. Protocol reference

### 5.1 Catalog

```
GET https://api.alloha.tv/?token=<token>&kp=<kinopoisk_id>
```

```jsonc
{
  "data": {
    "name": "Игра Престолов",
    "iframe": "https://host/player?…",            // movies
    "seasons": {                                    // serials
      "1": { "episodes": {
        "1": { "translation": {
          "3": { "translation": "AlexFilm", "iframe": "https://host/?token_movie=…&translation=3&season=1&episode=1&token=…" }
        } } } } }
  }
}
```

Rules:
- `seasons` non-empty → serial, otherwise `data.iframe` is the movie player.
- Season/episode keys are numeric strings → parse to Int and sort numerically (string sort puts `10`
  before `2`).
- Translation without `iframe` is unusable; dedupe translations by `iframe`; sort by name.
- Errors: missing `data` → show `message`; no seasons and no iframe → "no source".

### 5.2 `bnsi` payload (source of truth)

Fetched by the page from `…/bnsi/…`. Parsed into `AllohaParsedSources`:

```jsonc
{
  "hlsSource": [
    {
      "audioId": "10",                         // fallback: array index
      "label": "(Russian) AniLibria",          // fallback: "Озвучка N"
      "default": true,
      "quality": {
        "1080": "https://cdn-a/…/master.m3u8 or https://cdn-b/…/master.m3u8",
        "720":  "//cdn-a/…/master.m3u8"
      }
    }
  ],
  "tracks": [
    { "kind": "captions", "label": "Русский", "language": "rus", "src": "//cdn-a/…/sub.vtt" }
  ]
}
```

Rules:
- Quality labels: numeric → append `p` (`1080` → `1080p`); keep the map sorted **ascending**, so
  `values.last()` is the best quality and the UI reverses the list.
- `url1 or url2`: take the first as the primary; the wrapper JS uses the second as a browser-side
  fallback on `403/500/503`.
- `//host/path` → `https://host/path`.
- A source (audio track) with no qualities is dropped. If no source survives →
  `AllohaSourceUnavailableException` → user-facing "no source for this dubbing".
- Subtitles: only `kind == "captions"`; format from the URL extension (`vtt`/`srt`/`ass`/`ssa`).
  Real payloads contain ~9 subtitle tracks (RU/UA/EN, ASS and SRT variants).

### 5.3 Session headers captured from the page

| Header | Source | Role |
|--------|--------|------|
| `authorizations` | page `setRequestHeader` | per-title token, stable for the session |
| `accepts-controls` | page `setRequestHeader`, refreshed by `config_update.edge_hash` | edge hash; CDN decrypts the path token with it |
| `borth` | page `setRequestHeader` | per-request nonce — **never replay** |
| `origin`, `referer` | synthesised from `location.origin` | required |
| `user-agent` | WebView settings | desktop Chrome, stable |
| `sec-fetch-dest/mode/site` | synthesised | browser-like |

Bridge callbacks: `onReady(bnsi, headers)`, `onM3u8Refreshed(url, headers)`,
`onConfigUpdate(edgeHash, ttl, headers)`, `onStreamHeaders(headers)`,
`onDubbingUnavailable()`, `onLog(message)`.

### 5.4 Signed master URL

The page itself requests `…/master.m3u8` with a path token. The wrapper reports that URL
(`onM3u8Refreshed`) as soon as it is seen; the session stores it as `masterUrl`.

The proxy serves `/master.m3u8` from the **per-quality bnsi URL** of the selected audio track and
falls back to `masterUrl`; `masterUrl` is also the base for `rewriteToCurrentPath` and for recovering
stale sessions. Both URLs are valid in practice; the signed one is the freshest.

---

## 6. Session opener contract

`AllohaSessionOpener.open(iframeUrl, preferredQuality): OpenResult`

- `Ready(session)` — proxy is already started and listening.
- `Unavailable` — page rendered "озвучка недоступна", or bnsi has no sources.
- `Failed` — timeout / no bridge data.

Layered timeouts (all intentionally):

| Constant | Value | Meaning |
|----------|-------|---------|
| `TIMEOUT_MS` | 30 s | hard ceiling for the whole open |
| `NO_SIGNAL_TIMEOUT_MS` | 10 s | nothing arrived → reload the wrapper |
| `MAX_WRAPPER_RELOADS` | 2 | 3 attempts total inside the 30 s budget |
| `MASTER_WAIT_TIMEOUT_MS` | 6 s | bnsi is ready but no signed master → deliver with the bnsi master |
| `HOST_CHANGE_CONFIG_WAIT_MS` | 10 s | master host changed → wait for a fresh `config_update` |
| `MAX_HOST_CHANGE_RESTARTS` | 3 | restart budget when no `config_update` was ever seen |
| `IDENTITY_RESET_MIN_INTERVAL_MS` | 30 s | rate-limit cookie/storage resets |

Delivery rule: a session is delivered when **sources parsed AND a master URL exists**, or when the
master wait expires with sources parsed.

Identity reset: if the CDN rejected the session identity (`session_blocked` / `token_decrypt`), the
next open clears cookies + DOM storage + idle WebViews (rate-limited).

Host-change rule (mid-session):
- rotating → accept the new master into the staged state;
- host changed and `config_update` was seen → hold the master until a fresh edge hash arrives
  (fallback: full restart);
- host changed and `config_update` was never seen → restart immediately (budget 3), then accept.

---

## 7. Live session state

`AllohaSession` owns:

- `headers` (`ConcurrentHashMap`), `masterUrl`, `expiry`, `generation`.
- `generation` increments on initial `initialize` and on every committed rotation. The proxy watches
  it to rebuild its transport and to know that a refresh landed.
- Selected audio id + quality label. Selection is preserved across rotations by id/label, never by
  URL.
- `refresh()` starts a **staged rotation**: reload the wrapper, collect new signals into a shadow
  state, commit atomically when complete (`hasReady && hasRefreshedMaster`,
  plus `hasConfigUpdate` only if this session has ever seen one) or after 8 s.
- `onStateChanged` callback for the UI (audio list / quality ladder may change after a rotation).

Audio/quality switching is just a new loopback URL against the same live session — no re-open.

---

## 8. Proxy contract

Endpoints (all require `token=<per-session UUID>`; loopback ports are not isolated between apps):

```
GET /master.m3u8?token=…[&audio=<audioId>][&quality=<label>]   → rewrite master for that pair
GET /proxy?url=<base64url>&token=…                             → forward any session URL
```

Playlist handling:
- Fetch with session headers, rewrite every URI to `/proxy?url=…&token=…`, collect segment list,
  prefetch the first 2, keep the recent list for look-ahead prefetch.
- `#EXT-X-MEDIA` and `#EXT-X-MAP` `URI="…"` attributes are rewritten too.

Segment handling:
- LRU cache of 4 segments, in-flight de-duplication (`computeIfAbsent`), prefetch 2 ahead.
- Range requests bypass the cache and forward `Range`/`If-Range`; `206` and `Content-Range` are
  preserved.
- ASS/SSA subtitles are rewritten in memory (`\pos` fix) and served as `text/x-ssa`.

Recovery ladder per request:
1. Exact request; on `403` retry once with `Connection: close` on an evicted pool.
2. Stale segment path → rewrite to the current master path, retry.
3. Escalate: one background session `refresh()` (single-flight), hold the loopback request up to
   **14 s**, retry on the new generation.
4. Audio `-a1.ts/-a2.ts` → empty 188-byte TS packet; everything else → `503`.

Skip the hold entirely for `token_decrypt`: a refresh reloads the same WebView and returns the same
edge hash — fail fast so the caller can escalate to a genuinely fresh session.

On session generation change: **swap** `OkHttpClient` + `ConnectionPool` (do not just `evictAll()`,
which only closes idle connections). Keep the segment cache and in-flight fetches.

---

## 9. Playback, prefetch and restore

Player (`AllohaPlayerController`):
- Media3 ExoPlayer, buffer `20 s / 60 s / 1 s / 2.5 s`, seek increments 10 s.
- Preferred audio/text language `ru`; if the HLS stream exposes multiple audio groups, override to
  the first group once per media item.
- Subtitles: every bnsi caption track becomes a side-loaded `SubtitleConfiguration` pointing at the
  proxy (`text/vtt`, `application/x-subrip`, `text/x-ssa`). All groups start disabled; the UI selects
  one via a `TrackSelectionOverride`, or `setTrackTypeDisabled(TEXT, true)` for "off".
- Quality/audio change = `setMediaItem` with a new loopback URL at the current position,
  preserving play/pause.

Prefetch (the main latency win):
- After the first frame, 2.5 s later, open the session for the currently selected iframe in the
  background (one at a time, foreground only). Selection changes while idle use a 700 ms debounce.
- On "Watch", consume the prefetched session if the iframe matches; otherwise await it at most 4 s,
  then cancel and open fresh.
- Measured on device: tap → playback **7.9 s** without prefetch, **0.13 s** with a warm prefetch
  (≤1.6 s if the prefetch was still finishing).

Restore after backgrounding:
- Snapshot `{title, episodeLabel, iframeUrl, audioId, qualityLabel, positionMs, durationMs,
  wasPlaying, updatedAt}` every 5 s while playing, and on `onStop`.
- On `onStop`: remember whether it was playing, pause, persist.
- On `onStart`: if away ≥ 45 s, or the session expired / is rotating / the player has an error →
  rebuild the session, `seekTo(saved)`, restore audio/quality, play. Otherwise just resume.
- After process death: load the snapshot (TTL 7 days) and show a "Continue watching" card.

Keep-alive:
- While foreground, a 5 s loop refreshes the session when its TTL has ≤ 25 s left.
- While backgrounded, do nothing — the session will usually be dead on return; the restore path
  handles it.

---

## 10. Porting playbook (any language / framework)

Build these four components in this order. Keep the names for parity.

1. **JS wrapper (no native logic).** Port `AllohaWrapperScript` literally. It must:
   install hooks on poll, capture `bnsi` from both XHR and `fetch`, capture the signed master from
   XHR `load`/`loadend` and `fetch`, accumulate headers from `setRequestHeader` and `fetch` init,
   hook `WebSocket` for `config_update` + 25 s heartbeat, spoof `visibilityState/hidden`, mute all
   `<video>/<audio>` (including ad-inserted ones via `MutationObserver`), watch for
   "озвучка недоступна", and click play every 300 ms until the session is captured, then every
   1.5 s. Expose exactly the six bridge callbacks from §5.3.
2. **Session opener + bridge.** Poll-install wrapper in an embedded browser; parse `bnsi` and
   headers off the UI thread; implement §6 timeouts and reloads. Deliver only when
   `sources && master`.
3. **Live session + proxy.** Implement §7 and §8. The proxy is a plain HTTP server; no framework
   needed. Do not skip the per-session token, the IPv4 loopback bind, the header filter, or the
   client hints.
4. **Player + persistence.** Point the HLS player at the loopback master, side-load subtitles,
   implement prefetch and the background restore snapshot.

Language mapping:

| Concern | Android | iOS (reference: `neomovies-apple`) | Desktop |
|---------|---------|------------------------------------|---------|
| Embedded browser | `WebView` + `addJavascriptInterface` + `loadDataWithBaseURL` | `WKWebView` + `WKScriptMessageHandler` + `loadHTMLString(baseURL:)` | CEF / WebView2 |
| Local server | `ServerSocket` + coroutines | `NWListener` / `GCDAsyncSocket` | any HTTP server |
| HTTP client | OkHttp | `URLSession` | any |
| HLS player | Media3 | AVPlayer | ExoPlayer / libVLC / hls.js |

Do not port the Android UI. The portable core is: wrapper script, parser, session state, proxy,
restore policy. The UI only renders state and sends commands.

---

## 11. Timing table (copy verbatim unless you have device measurements)

| Where | Constant | Value |
|-------|----------|-------|
| Opener | total / no-signal / master wait | 30 s / 10 s / 6 s |
| Opener | wrapper reloads / host-change restarts | 2 / 3 |
| Opener | host-change config wait / identity reset rate limit | 10 s / 30 s |
| Session | staged commit timeout | 8 s |
| Proxy | segment hold for refresh | 14 s |
| Proxy | session refresh wait / poll | 20 s / 150 ms |
| Proxy | segment cache / prefetch | 4 / 2 |
| Proxy | accepted request line | 32 KB |
| Proxy | socket read timeout | 15 s |
| WebView JS | install poll / install window | 20 ms / 8 s |
| WebView JS | play-click interval | 300 ms → 1.5 s after capture |
| WebView JS | heartbeat | 25 s |
| ViewModel | snapshot interval / keep-alive threshold | 5 s / 45 s |
| ViewModel | refresh lead / maintenance interval | 25 s / 5 s |
| ViewModel | prefetch debounce / start delay / await | 700 ms / 2.5 s / 4 s |
| ViewModel | catalog cache TTL / snapshot TTL | 15 min / 7 days |
| ViewModel | recovery attempts / delay | 3 / 1.2 s |

---

## 12. Failure ladder

| Level | Owner | Behaviour |
|-------|-------|-----------|
| Segment | proxy | 403 → exact retry → path rewrite → hold 14 s → `503` |
| Playlist | proxy | same, holding allowed |
| Session | session | staged `refresh()`, commit ≤ 8 s |
| Stream | ViewModel | player error → rebuild session (≤ 3), seek back, resume |
| Hint | UI | error card with "Retry"; catalog errors retry the catalog |

---

## 13. Debugging

Everything is logged under the single tag `Alloha`, with a bracketed subsystem:
`[Opener] [Session] [Proxy] [WebView] [ViewModel] [Api] [Parser]`.

```bash
adb logcat -s Alloha                 # debug build: always on
adb shell setprop log.tag.Alloha DEBUG   # release build
```

Markers worth knowing:

- `bnsi parsed audio=… subtitles=…` — sources captured.
- `master refreshed host=…` — signed master captured.
- `session ready ttl=…s` — session delivered; proxy starts next.
- `using prefetched session` — the tap reused a warm session.
- `no signal after 10000ms` — the page never delivered; a reload is happening.
- `session timed out … streamReady=… refreshedMaster=…` — which signal was missing.
- `staged commit timed out …` — rotation could not complete in 8 s.
- `CDN failure code=… xVd=…` — `client_blocked` = headers, `token_decrypt` = stale master,
  `session_blocked` = session identity rejected.
- `rebuilding session after …ms in background` — restore path took over.

There is no on-disk HTTP trace anymore. Add logging temporarily; do not ship tracing.

---

## 14. Tests

Pure logic must stay testable without Android:

```bash
./gradlew :app:testDebugUnitTest
```

Covered: catalog parsing, `bnsi` parsing (`or` fallback, `//` URLs, quality labels, captions,
unavailable), ASS margin→`\pos` rewrite, proxy URL helpers (query, content types, stale-path
rewrite), snapshot codec, duration formatting. Add a test for any new pure helper before wiring it
into the session/proxy.

---

## 15. Build & run

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease        # R8, unsigned
./gradlew :app:installDebug
adb shell am start -n org.emilumiq.alloha/.MainActivity
```

The Gradle daemon JVM pin file was removed because it required JDK 21; builds run on the local
JDK. Do not re-add it unless every build machine has that JDK.

---

## 16. Code rules for this repo

- Zero comments unless a workaround names an external bug or a hidden invariant, one line max.
- Delete unused code immediately; no dead params, constants or files.
- One responsibility per file; no boolean flags that switch behaviour inside a function — split it.
- No `!!`, no `@Suppress`, no silent `?: null`, no `delay` used as synchronisation.
- Pure logic goes in objects/functions that unit tests can call; Android types stay at the edges.
- `AllohaSessionBridge` stays public and kept by R8: the WebView resolves its methods by name.
- Theme: Material 3 with Monet dynamic colour on Android 12+; Material icons, no custom glyphs.

---

## 17. Reference material

- This repo: Android implementation (session, proxy, restore, prefetch).
- `YummyTV/docs/alloha-player.md`: production notes from a TV app, same protocol, includes measured
  failure modes and download-path details.
- `neomovies-apple`: Swift/iOS take on the same session (WKWebView + AVPlayer), useful for a
  cross-check of the JS bridge and master capture.
- Git history: v1 of this repo was a minimal extractor + proxy; the current structure supersedes it.
