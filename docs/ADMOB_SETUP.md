# AdMob setup and release checklist (Phase 10)

Everything in this app is still completely free - ads never gate any feature. Two ad formats are
integrated, both currently running on **Google's official TEST ad IDs** (which always serve
labeled test ads, no AdMob account needed):

1. One restrained anchored adaptive banner at the bottom of the app's top-level screens (the five
   navigation tabs) and nowhere else.
2. A capped interstitial, shown at the single natural transition of *closing the read-only
   Insights screen* - at most once every 10 minutes, never at launch, never when a transaction
   was entered/edited/saved/deleted in the session, never during backup/restore/export, never
   over dialogs, and never before consent/initialization allows ad requests. Any interaction with
   a transaction details sheet inside Insights (including deletes) suppresses the interstitial
   for that session.

All secondary screens (add/edit transaction, backup/restore, recurring rules, savings goals, the
details sheet) are otherwise ad-free.

At the moment the app uses **Google's official TEST ad IDs**, which serve test ads only. Follow
this guide to switch to real monetization before publishing.

## 1. What is already configured in the project

| Piece | Location |
|---|---|
| AdMob App ID + banner ad unit IDs (test values) | `app/src/main/res/values/ads.xml` |
| Manifest metadata (`com.google.android.gms.ads.APPLICATION_ID`) | `app/src/main/AndroidManifest.xml` |
| Consent (UMP) + SDK initialization | `app/src/main/java/com/litemobiletools/dailyexpensetracker/ads/AdSupport.java` |
| Banner loader (one instance, dedup, backoff) | `app/src/main/java/com/litemobiletools/dailyexpensetracker/ui/main/MainActivity.java` |
| Release-safety gate (pure, unit tested) | `app/src/main/java/com/litemobiletools/dailyexpensetracker/util/AdReleaseGuard.java` |
| Consent entry point in Settings | `SettingsFragment` (row appears only when UMP requires it) |

SDK versions (already pinned in `gradle/libs.versions.toml`):

- `com.google.android.gms:play-services-ads:25.5.0` (official Google Mobile Ads SDK, current stable)
- `com.google.android.ump:user-messaging-platform:4.0.0` (UMP consent SDK, matched pair per
  Google's release notes)

Note: Google's newer "GMA Next-Gen SDK" (`com.google.android.libraries.ads.mobile.sdk`) was
evaluated; at the time of this phase it is a very young 1.x release. The stable Mobile Ads SDK
chosen here is fully supported and correct for this Java project; migration is optional later.

## 2. Creating the real AdMob configuration (manual, requires an AdMob account)

1. Sign up at <https://apps.admob.com> with a Google account.
2. **Apps > Add app**. Choose *Android*, published, and set the package name to exactly
   `com.litemobiletools.dailyexpensetracker` (must match `applicationId`).
3. Note the app's **App ID** (`ca-app-pub-XXXXXXXXXXXXXXXX~NNNNNNNNNN`).
4. **Ads > Ad units > Add ad unit > Banner**, name it, and note its
   **Ad unit ID** (`ca-app-pub-XXXXXXXXXXXXXXXX/NNNNNNNNNN`).
5. **Privacy & messaging** (AdMob menu): create a **GDPR message** and a **US state regulations
   message** (and any other programs Google marks as required for you; under *other regions*
   leave consent OFF unless you know you need it). Mark your Android app in every message. This
   step is what makes the UMP consent form and the "Ad privacy options" Settings entry appear.
6. Optionally create an **OMP-linked Google Play listing** and complete AdMob's payment profile
   and address verification (PIN) once revenue thresholds apply.

### Replace the test IDs

Edit `app/src/main/res/values/ads.xml` and replace ONLY these three values:

```xml
<string name="admob_app_id" translatable="false">ca-app-pub-3940256099942544~3347511713</string>
<string name="admob_banner_unit_id" translatable="false">ca-app-pub-3940256099942544/6300978111</string>
<string name="admob_interstitial_unit_id" translatable="false">ca-app-pub-3940256099942544/1033173712</string>
```

- Never put real ad unit IDs in Java code, and never put the **App ID** in an ad request.
- The units and the App ID are shape-checked (`~` vs `/`) by `AdReleaseGuard`, so swapping them by
  accident is caught before any request is made.
- The banner is requested with Google's canonical **fixed BANNER (320x50 dp)** size - the exact
  combination Google's quick-start pairs with the demo/test unit above, which is the most reliable
  way to make test ads show. To switch to an anchored adaptive banner instead, change one line in
  `MainActivity.createBanner()` to
  `adView.setAdSize(AdSize.getLargeAnchoredAdaptiveBannerAdSize(this, containerWidthDp))` and use
  the adaptive-specific test unit `ca-app-pub-3940256099942544/9214589741`.
- If you decide the interstitial should not be shown at all, remove
  `admob_interstitial_unit_id` from `ads.xml` and the value `null` in `AdSupport.adsAllowedForThisBuild`
  keeps the rest of the ads working (the guard accepts a null interstitial unit).
- **Release safety:** a release build that still contains the test IDs above (or a malformed ID)
  disables ad requests entirely - the app runs ad-free instead of shipping accidental
  configuration. Debug builds keep showing test ads so you can verify the ad plumbing.

## 3. Release checklist (do all of these before publishing)

1. `ads.xml` contains your real App ID and a real banner ad unit ID (not the test pair).
2. `AndroidManifest.xml` still has exactly ONE
   `com.google.android.gms.ads.APPLICATION_ID` `<meta-data>` referencing `@string/admob_app_id`.
3. Package name in AdMob matches this project's `applicationId`.
4. The consent messages exist in AdMob's Privacy & messaging for this app (otherwise EEA/UK/
   relevant US users see no required consent flow and personalized-ads policy may apply
   incorrectly).
5. Your app's publicly available privacy policy (required by Google Play for this category AND
   referenced from the in-app dialog's note) discloses Google Mobile Ads SDK usage and links to
   Google's Business Privacy page / advertising policy. The in-app text must mirror reality;
   the current in-app text honestly says the public page is not yet published - replace it with
   your real URL when it exists.
6. **Play Console > App content > Advertising ID**: declare that the app uses an advertising ID
   for advertising purposes. The Mobile Ads SDK adds the `AD_ID` permission through manifest
   merging (this is required and expected for AdMob); the declaration must be accurate.
7. AdMob banner refresh: configure an app-wide refresh interval on the ad unit (none of the code
   refreshes banners itself; a 30-60 s refresh rate is common).
8. Never click your own live ads, not even "to test" - that suspends accounts. Re-verify the
   integration instead with Google's official test IDs and, on your own device, the test-device
   configuration described in section 4.

## 4. Testing the ads (development)

- Debug builds automatically use whichever IDs are in `ads.xml` - today those are Google's
  official test IDs, so a debug build shows the labeled "Test Ad" banner without touching any
  real AdMob data. That is the intended development loop.
- To force test ads with your real IDs on your own device, use `RequestConfiguration`
  `TestDeviceIds` (officially supported) rather than clicking live ads.

#### Why might test ads not show? (troubleshooting)

1. **No internet**: ads require connectivity when the app loads them; offline the app simply
   stays ad-free.
2. **Restricted Google service**: test ads come from Google's ad servers via Play services;
   restricted-data settings and DNS-level ad blockers can block them.
3. **Consent update failed**: normally the session then retries at the next launch. In DEBUG
   builds on test IDs the app deliberately falls back to serving test ads anyway, and a failed
   banner load shows a short debug-only toast with the exact error and message - read it:
   codes 1-3 (network / invalid request / no-fill) tell you precisely where the block is.
4. **First-run timing**: the consent check, SDK initialization, and the banner load run in
   sequence in the background, so the banner appears within seconds but not at the exact launch
   frame.

### Testing the consent flow (UMP)

Consent forms only appear when AdMob's Privacy & messaging is configured AND your country is in
one of the configured regions. To test anywhere in the world from a debug build, Google supports
consent debug settings. If needed, temporarily modify `AdSupport.buildParameters()` in a private
branch (never commit to the shipping code) to add:

```java
ConsentDebugSettings debug = new ConsentDebugSettings.Builder(context)
        .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
        .addTestDeviceHashedId("<your device's debugSignature hashed ID, printed by UMP>")
        .build();
// then: new ConsentRequestParameters.Builder().setConsentDebugSettings(debug)...
```

The hashed ID is printed to Logcat as `**_mdp` debugSignature when the update runs on your device.
After consent is gathered, use **Settings > Ad privacy options** to revisit the choices (the row
appears only when your consent configuration requires the entry point). While testing, the useful
app actions are:

- `adb shell pm clear com.litemobiletools.dailyexpensetracker` (resets stored consent)
- Settings row verify: appears only when UMP's `privacyOptionsRequirementStatus == REQUIRED`.

## 5. Interstitial placement decision

The phase requirements initially allowed skipping interstitials entirely when no safe placement
exists - and that was correct at first: every screen transition in this expense tracker sits on
the spec's "never" list (launch, transaction CRUD, backup, restore, export, confirmation dialogs,
plain navigation taps all directly touch financial records). The demo test configuration later
explicitly included the official test interstitial unit, so the integration adds exactly ONE
placement, chosen as the least intrusive transition available and wrapped in the strictest of the
phase's own rules:

- Show site: finishing the **read-only** Insights screen (returning to the main screens after
  viewing charts). Nothing financial is performed or pending at that moment by definition of the
  session guard below.
- Suppression guard: if the user opened a transaction details sheet during that Insights session
  (details can lead to edits and deletes), the interstitial is skipped for that entire session.
- Frequency cap: at most ONE interstitial every 10 minutes, tracked in process memory only (a
  fresh launch starts clean); no repeated ads in a short session.
- Preloading: the first interstitial loads once consent + SDK initialization complete; each shown
  ad reloads one successor. Failed loads are retried only at the next capped show attempt - there
  is never a retry loop.
- Never shown: at launch; on add/edit/save/delete of transactions (guarded); during backup,
  restore, export, or any confirmation; over any dialogs; while the user merely taps normal
  navigation (tabs).
- If no ad is available or eligible, the requested action (finishing the screen) proceeds
  normally and instantly.

## 6. Reporting notes (honest status)

- Verified so far: compile, unit tests (incl. `AdReleaseGuardTest`), lint, layout inspection.
- Requires on-device verification by you: test banner rendering, consent form with configured
  messages, storage/ad_privacy row visibility, light/dark appearance, offline behavior.
- Requires a real AdMob account: production ads, revenue, policy approval. Nothing in this code
  claims account approval or monetization.
