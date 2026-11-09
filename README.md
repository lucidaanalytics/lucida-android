# Lucida Android SDK

## Install

From GitHub, [lucidaanalytics/lucida-android](https://github.com/lucidaanalytics/lucida-android), release `v0.1.0`:

```kotlin
// settings.gradle.kts / build.gradle.kts
repositories { maven("https://jitpack.io") }
dependencies {
    implementation("com.github.lucidaanalytics.lucida-android:lucida-android:v0.1.0")
    // pure JVM core only: ...:lucida-core:v0.1.0; also ...:lucida-compose:v0.1.0, ...:lucida-openfeature:v0.1.0
}
```

JitPack builds the tag on first request. The release also attaches the `.aar` and `.jar` files. Maven Central (`io.github.lucidaanalytics:lucida-android`) is coming.


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

## Install attribution

On first open `$app_installed` carries the Google Play Install Referrer: `$install_store=google_play`,
`$install_referrer` (raw), `$install_referrer_click_ts`, `$install_begin_ts`, plus parsed `$utm_*` and click ids
(`$gclid`, `$gbraid`, `$wbraid`, `$fbclid`, `$ttclid`, `$msclkid`). Add `com.android.installreferrer:installreferrer`
(Apache-2.0) to your app; it is `compileOnly` here, and without it the event carries `$install_store` only. Disable
with `AndroidOptions(installAttribution = false)`. Opted-out users send nothing.

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

### Session replay: privacy additions

Every remote value below arrives in `GET /v1/config` and only ever adds masking to what the app configured.

- **Masked screens** (P-M17): `ReplayConfig(maskScreens = listOf("Checkout*"))` plus the project's `replay_mask_screens`
  (case-insensitive, `*` = any run of characters, whole screen name = the Activity's simple name). On a matching screen every
  text, input value and image is masked whatever the global switches or `lc-unmask` tags / Compose unmask markers say;
  screenshots paint them all over.
- **Text patterns** (P-M46): `ReplayConfig(maskTextPatterns = listOf("\\d{4,}"))` plus `replay_mask_text_patterns` (regular
  expressions; one that does not compile is ignored). Matches in any text the wireframe would show become `*` (whitespace
  kept); in screenshot mode a view whose text matches is painted over.
- **`$dead_swipe`** (P-M46, `ReplayConfig(captureDeadSwipes = true)` by default, needs `captureTouches`): a swipe of 48 dp or
  more after which the view tree is unchanged ~700 ms later. Properties: `$screen_name`, `$swipe_direction`
  (`left|right|up|down`), `$swipe_distance` (dp). Never text or view content; at most one per 2 s.
- **Controlled exposure** (P-L12): when the project sends `replay_exposure_fields` and `replay_exposure_key`, a listed field's
  value is RSA-OAEP (SHA-256 + MGF1-SHA-256) encrypted on device and sent as `$sensitive_capture` (`$exposure_field`,
  `$exposure_kid`, `$exposure_ciphertext`, `$screen_name`, `$exposure_truncated` when cut to 190 bytes). Only an audited
  reveal on the server can read it. Explicit: `LucidaAndroid.client.captureSensitive("card_number", value)`. Automatic: a
  non-password `EditText` whose tag string or resource entry name is listed is read during the snapshot walk (it stays masked
  in the replay and can never be unmasked). Password inputs are never captured; identical values are sent once per session;
  nothing is persisted.

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

Not built: push registration (needs a scope decision), network/log capture in replay.

## In-app guides

Kinds `modal`, `banner`, `announcement`, `slideout`, `tour`, `carousel` (Back / Next with a slide pager) and, with an
anchoring presenter, `tooltip` / `hotspot`. A step's `native_id` anchors it to a native view by its String tag or resource entry name (`AlertGuidePresenter` implements `AnchoredGuidePresenter`; classic Views, not Compose test tags): the bubble is drawn next
to the view and follows it on scroll and relayout; a hotspot is a pulsing dot that opens the step. When the view does not
appear within `wait_ms` (default 3 s, max 15 s) the SDK sends `$guide_target_missing {step_id, selector}` and shows the step
unanchored; a view gone for 2 s closes the step. The resolved `appearance` tokens (background, text, accent, radius,
font) apply, with `appearance.dark.tokens` when `theme_mode` is dark, or auto and the device is dark.

## Fallbacks, flag details, OpenFeature

`FlagOptions(fallbacks = mapOf("new_nav" to false))` serves that value, reason `fallback`, when a flag has no value (missing, failed load,
cold start); fallback reads emit no `$feature_flag_called`. `lucida.flags.getFeatureFlagDetails("key")` returns `FlagDetails`
(value, payload, variant, raw `reason`, `conditionIndex`, `requestId`, `evaluatedLocally`), or null for an unknown key without a fallback.
`lucida.flags.onFlagsStatus { CHANGED | STALE | ERROR }` reports reloads.

Module `lucida-openfeature` (`dev.openfeature:kotlin-sdk:0.6.2`, Apache-2.0), static-context provider:

```kotlin
OpenFeatureAPI.setProviderAndWait(LucidaProvider(lucida), ImmutableContext(targetingKey = "u1", attributes = mapOf("plan" to Value.String("pro"))))
val on = OpenFeatureAPI.getClient().getBooleanValue("new_nav", false)
```

`groups` / `groupProperties` attributes map to Lucida groups; the rest are person properties. Reads carry reason and
`flagMetadata` (`lucidaReason`, `conditionIndex`, `requestId`, `evaluatedLocally`); `track()` maps to `capture`.

## Develop

`make sdk-android` (needs a JDK 17+; `brew install openjdk@21`) or
`cd sdks/android && ./gradlew :lucida-core:test`. The test run reads the shared vectors via
`../../../testdata/flags`.

Gaps: scroll offsets and Compose semantics are not captured (View system only); Compose `lucidaMask()`/`lucidaUnmask()` bounds are kept in screen dp and shifted onto the walked decor view, so dialogs and inset windows line up;
tap coordinates use raw screen px / density against decor-view dp.

---

This repository is a read-only mirror of the Lucida monorepo (synced by a script, never edited by hand); releases and provenance attestations are built here by GitHub Actions. Issues are welcome, changes land upstream.
