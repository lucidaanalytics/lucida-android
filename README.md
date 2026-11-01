# Lucida Android SDK

Kotlin. Two Gradle modules:

- `lucida-core`: pure Kotlin/JVM (no Android classes). Capture queue, identity, consent, flags, lifecycle
  events, wireframe replay serializer. Tested with JUnit, no emulator.
- `lucida-android`: thin Android adapter. `SharedPrefsStore` (SharedPreferences), `ViewHierarchy`
  (`android.view.View` to neutral `ViewNode`), activity lifecycle callbacks, tap capture. Only compiled when an
  Android SDK is present (`ANDROID_HOME` or `~/Library/Android/sdk`).

```kotlin
val lucida = LucidaAndroid.init(
    application, "lc_pk_...", "https://ingest.example.com",
    consentMode = ConsentMode.OPT_IN,   // nothing is stored or sent until optIn()
    replay = ReplayConfig(),            // null (default) = no replay
)
lucida.capture("checkout_started", mapOf("items" to 3))
lucida.identify("user-42", set = mapOf("plan" to "pro"))
lucida.flags.isEnabled("new-checkout")
lucida.optIn()
```

Pure-JVM use (tests, other hosts): `Lucida(CoreConfig(apiKey, host, UrlConnectionHttp(), myStore), appVersion = "1.0")`.

## Wire contract (same as `sdks/browser/README.md`)

- Events: `POST /v1/batch?compression=gzip`, body `{api_key, batch, sent_at}`; events carry `uuid` (UUIDv7, the
  dedupe key), `$session_id`, `$window_id`, `$lib`, `$lib_version`. At-least-once: events stay persisted until a
  2xx; 408/429/5xx/network errors retry with jittered exponential backoff (5 tries), other 4xx drop.
- Lifecycle: `$app_installed`, `$app_updated` (`$previous_app_version`), `$app_opened` (`from_background`),
  `$app_backgrounded` (flushes). Screens: `$screen` with `$screen_name` (one per Activity resume).
- Identity: `identify` (links `$anon_distinct_id` only while anonymous), `alias`, `reset`, `register*`, `group`.
- Consent: opt-in mode holds nothing on disk (identity, session, super props, groups stay in memory) until
  `optIn()`; `optOut()` sends `$opt_out`, then wipes the persisted queue.
- Flags: local evaluation over supplied definitions (`flagDefinitions`) passing all three vector files in
  `testdata/flags`; flags needing the server (cohorts) or without definitions use `POST /v1/flags`. The gateway's
  `/v1/flags/definitions` needs a secret key, so mobile apps bootstrap definitions themselves or rely on server eval.

## Wireframe replay

`POST /v1/replay?compression=gzip`, body `{api_key, session_id, window_id, seq, events}`, `seq` monotonic per window
and reset when the session rotates. Events are rrweb-shaped so `crates/lc-replay` decodes and re-scrubs them
unchanged: Meta (`href` = `app://<Activity>`, dp width/height), FullSnapshot (document, html, head, body, absolutely
positioned elements; `button` for Buttons, `input` for EditTexts, `span` for text, `div` otherwise) and tap
MouseInteraction Click events (`source 2, type 2, id` = deepest hit node).

Privacy: all text is starred (whitespace kept, the same rule as lc-replay `stars`) unless
`ReplayConfig(maskAllText = false)`; input values are never serialised under any setting; tag a view
`lc-block` (empty box, subtree dropped), `lc-ignore` (omitted) or `lc-mask` (mask even when the global switch is off).
Replay needs both `analytics` and `replay` consent.

## Hooks, person profiles, flags, privacy (options)

`LucidaAndroid.init(..., options = AndroidOptions(...))` carries the tuning knobs; the pure `CoreConfig` has the same fields.

- `beforeSend`: ordered `(event) -> event?` hooks. Return a changed map to amend, `null` to drop. A hook that throws is
  logged (when `debug`) and skipped: the event continues as it was before that hook. `sampleHook(0.1)` keeps ~10% and
  stamps `$sample_rate`.
- `personProfiles`: `ALWAYS` (default), `IDENTIFIED_ONLY` (events carry `$process_person_profile: false` until
  identify/alias/group/person-property write; persisted, cleared by `reset()`), `NEVER` (identify/alias/setPersonProperties
  become no-ops with a debug warning).
- Flags: `bootstrap = Bootstrap(distinctId, isIdentified, flags, payloads)`; `FlagOptions(sendFeatureFlagEvent, preload,
  evaluationContexts)`; `flags.onFeatureFlags { }`; `flags.setPersonPropertiesForFlags(props, reload = true)`,
  `setGroupPropertiesForFlags`, and the reset variants.
- Queue: `flushAt`, `flushIntervalMs`, `maxQueueSize`, `maxBatchSize`, `maxRetries`, `retryDelayMs`, `requestTimeoutMs`,
  `dataMode = DataMode.WIFI`, `sessionIdleMs/MaxMs`, `disableGeoip`, `disabled`, `lucida.debug(true)`,
  `queueCipher` (a `QueueCipher` port: encrypts only the persisted queue).
- Person helpers: `setPersonProperties`, `capture(event, props, set, setOnce)`, `getAnonymousId`, `reuseAnonymousId`,
  `isOptedOut`, `register*`, `setConsent`.
- Deep links: `$deep_link_opened` (url, query, referrer) for the launching intent; call `LucidaAndroid.handleDeepLink(intent)` from `onNewIntent`.
- Tracing headers: `tracingHeaders = listOf("api.example.com", ".corp.io")`, then `lucida.tracing.apply(httpUrlConnection)`
  adds `X-Lucida-Distinct-Id` / `X-Lucida-Session-Id` (never to the Lucida host; only with analytics consent). No OkHttp dependency exists here, so
  the helper targets `HttpURLConnection`.
- Autocapture (`AutocaptureConfig(enabled = true, ...)`): `$autocapture` taps (`$event_type` touch) and toggle changes with
  `ignoreLabels`, `maxElements`, `propsAllowList`, and `rageTap` (`$rageclick`). Tag a view `lc-no-capture` to exclude it and its
  subtree. Text-field values are never read.
- Replay: `ReplayConfig(maskAllText, maskAllTextInputs, maskAllImages, maskSandboxedViews, sampleRate, throttleMs)`, view tag
  `lc-unmask`, and `lucida.replay.start()/stop()/isActive`. Password inputs stay masked under any setting. Sampling is decided per session.

Not built: push registration (needs a scope decision), screenshot-mode replay, network/log capture in replay.

## Develop

`make sdk-android` (needs a JDK 17+; `brew install openjdk@21`) or
`cd sdks/android && ./gradlew :lucida-core:test`. The test run reads the shared vectors via
`../../../testdata/flags`.

Gaps: scroll offsets and Compose semantics are not captured (View system only); screenshot mode is not built;
tap coordinates use raw screen px / density against decor-view dp.

---

This repository is a read-only mirror of the Lucida monorepo (synced by a script, never edited by hand); releases and provenance attestations are built here by GitHub Actions. Issues are welcome, changes land upstream.
