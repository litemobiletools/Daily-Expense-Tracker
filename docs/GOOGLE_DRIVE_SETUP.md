# Google Drive setup (Phase 6)

The app can connect to the user's **own** Google Drive, using only the hidden `appDataFolder`. Phase 6 only
authorizes and does a read-only check; it never uploads, changes or deletes anything.

Real authorization cannot work until you complete the steps below in Google Cloud Console. **Nothing here
has been tested against a configured Google Cloud project**; until you do that, Connect shows
"Drive is not set up in this build" (Google error `DEVELOPER_ERROR`), which is the expected result.

## Values from this project

| Item | Value | Where it comes from |
| --- | --- | --- |
| Application ID (package name) | `com.litemobiletools.dailyexpensetracker` | `applicationId` in `app/build.gradle.kts` |
| Scope | `https://www.googleapis.com/auth/drive.appdata` | `DriveConstants.SCOPE_APPDATA` |
| Debug SHA-1 | Run the command below on your machine | Your `~/.android/debug.keystore` |

**You must supply** (they are not in the project and must not be guessed): your support email address,
a developer contact email, a public privacy policy URL, your release/upload keystore SHA-1, and the
Play App Signing SHA-1.

Debug SHA-1 (Windows, Android Studio's bundled JDK):

```
"C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -list -v ^
  -keystore "%USERPROFILE%\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android
```

Or run `gradlew signingReport`. Copy the `SHA1:` line. Debug keystores differ per computer; every developer
machine that runs the debug build needs its own Android OAuth client (or its fingerprint added to one).

## 1. Create the project

1. Open <https://console.cloud.google.com/> and sign in with the Google account that will own the project.
2. Project picker → **New project**. Name it (for example "Daily Expense Tracker"). Create it and select it.

## 2. Enable the Drive API

1. **APIs & Services → Library**.
2. Search **Google Drive API** → **Enable**.

Without this step Drive answers 403 `accessNotConfigured`, and the app shows the "not set up" state.

## 3. Branding and consent screen

Console: **Google Auth Platform** (older UI: *APIs & Services → OAuth consent screen*).

1. **Get started** → **App information**
   - App name: the name users will see (use the real app name).
   - User support email: **your** support address.
2. **Audience**: choose **External** (any Google account). **Internal** only works inside a Google Workspace organization.
3. **Contact information**: **your** developer contact email.
4. Agree to the API Services User Data Policy → **Create**.
5. **Branding** → set the app logo if you have one, and:
   - **Application home page** (optional but recommended).
   - **Application privacy policy link**: **your** public privacy policy URL. It must be a real, public page that
     describes this app. Do not use a placeholder; Google checks it during verification.
   - **Authorized domains**: the domain of those links (if you publish them).
   - Developer contact email.

## 4. Data access (scope)

**Google Auth Platform → Data Access → Add or remove scopes**

1. Add only `.../auth/drive.appdata` ("See, create, and delete its own configuration data in your Google Drive").
2. Do **not** add `drive`, `drive.file`, `drive.readonly`, profile, email, or anything else.
3. Save.

`drive.appdata` is classified as a **non-sensitive** scope, so it does not need Google's restricted/sensitive
scope security assessment.

## 5. Audience and test users

- While the publishing status is **Testing**, only accounts listed under **Audience → Test users** can authorize.
  Add the Google accounts you will test with (up to 100). In Testing mode, authorization can expire after a limited time
  and the user may need to approve again (the app shows "Permission needed again").
- To let everyone connect, move the app to **In production** (**Audience → Publish app**). For non-sensitive
  scopes, Google still requires the branding items (app name, logo if set, privacy policy link, authorized domains)
  to be valid, and may review them. Plan for that before release.

## 6. Create the Android OAuth client

**Google Auth Platform → Clients → Create client**

1. Application type: **Android**.
2. Name: for example "Android debug".
3. Package name: `com.litemobiletools.dailyexpensetracker`.
4. SHA-1 certificate fingerprint: the **debug** SHA-1 from above.
5. Create.

An Android client has **no client secret**, and the app needs no `client_id` in source: Google matches the
request by package name and signing certificate. Do **not** create or download a Web client secret for this
app, do not put any secret or JSON credential file in the app, and do not commit one to version control
(`.gitignore` blocks `client_secret*.json`, `google-services.json`, `*.keystore`, `*.jks`).

Create a **second** Android client for each other signing key:

| Build | Client name | SHA-1 to use |
| --- | --- | --- |
| Debug | Android debug | Debug keystore SHA-1 (above) |
| Release (sideload / upload key) | Android release | SHA-1 of your own release/upload keystore |
| Google Play (Play App Signing) | Android Play | Play Console → *Test and release → App integrity → App signing* → **App signing key certificate** SHA-1 |

If you use Play App Signing, users receive APKs signed by **Google's** key, not your upload key. If the
Play App Signing SHA-1 is missing from an Android client, Drive authorization fails for installs from Google Play even
though your own release build works. Add both fingerprints.

## 7. Check it

1. Install the debug build on a device or emulator with Google Play services and a **test user** signed in.
2. Open **Settings → Backup and restore → Connect Google Drive → Continue**, approve the Drive permission on Google's screen.
3. Expected: "Connected". This means Google issued a token **and** Drive answered a read-only listing of the
   hidden folder. No file is created.
4. **Disconnect** asks Google to withdraw the app's access. You can also review it at
   <https://myaccount.google.com/permissions>.

Troubleshooting:

| Symptom in the app | Likely cause |
| --- | --- |
| "Drive is not set up in this build" right after tapping Connect | No Android OAuth client for this package + SHA-1, or consent screen not configured (`DEVELOPER_ERROR`) |
| "Google status code 8" (INTERNAL_ERROR) or 10 | Google could not match this build to an OAuth client: check package name, the SHA-1 of the key that signed the installed APK, that the account is a Test user, and that the consent screen is saved. Also update Google Play services and retry. |
| Same state after approving | Drive API not enabled (403 `accessNotConfigured`) |
| "Permission not granted" | The user cancelled or left the Drive checkbox unticked |
| "Permission needed again" | Token expired or access was removed; approve again |
| "Google is unavailable" | Google Play services missing/out of date, 429, or 5xx |
| Account not accepted in Testing mode | The account is not listed under **Test users** |

## Verification and compliance requirements

- Google may require verification of branding (name, logo, privacy policy, domains) before the app can be used
  by the general public. This app has **not** been verified or approved by Google; do not state otherwise until
  the Cloud Console shows that status.
- A public **privacy policy URL** is required for the consent screen and for Google Play. It must describe
  what this app actually does: data stays on the device; optional Drive access to the app's hidden folder only;
  no analytics; ads (AdMob) once added; how users disconnect and delete data.
- Google Play **Data safety** form must reflect the real behavior, including Drive access and (once added) AdMob.
- The `INTERNET` permission is requested only for the Drive connection; traffic is HTTPS-only
  (`network_security_config.xml`).
- Data placed in `appDataFolder` is hidden from the Drive UI, protected by the user's Google account, **not**
  end-to-end encrypted by this app, and the user can clear it from Drive's *Settings → Manage apps*.

## Phase 7: manual backup and restore

Once Connect works (all steps above done), the same screen offers two **manual only** actions; there is no
scheduled or automatic backup.

- **Back Up Now**: writes a single versioned JSON file (all 8 Room tables plus the backing-up
  preferences) with a SHA-256 checksum, uploads it to `appDataFolder`, then keeps a local copy in the
  app's private `backups/` directory. Only one cloud backup is kept; uploading replaces the previous one.
- **Restore Backup**: lists the cloud backup, validates format version, app identity, app version,
  DB schema version, checksum and every row **before** touching the database, then replaces the local
  data in one Room transaction after an explicit confirmation and a local safety snapshot. If anything
  fails, the local data is rolled back and the cloud file is never deleted.

Troubleshooting additions:

| Symptom | Likely cause |
| --- | --- |
| "Not connected" prompt when tapping Back Up Now / Restore | Drive is not connected yet — use Connect first |
| Restore shows "app version / database version" rejection | The backup was made by a newer app or schema version; update the app first |
| "Checksum mismatch" / "corrupted" rejection | The file was modified or the download was damaged; nothing was changed locally |

## Phase 8: automatic backup (WorkManager)

Automatic backup is **off until the user turns it on** in *Backup and restore → Automatic backup*. When on, it reuses the exact Phase 6/7 machinery (the same `drive.appdata` scope, backup format, validation and upload code — there is no second implementation) on a schedule:

- **Frequency**: daily (~every 24 h) or weekly (~every 7 days), anchored around a **preferred time of day** chosen in the same card. Android may delay background work (battery optimization, Doze, connection availability), so the time is guaranteed to be *approximate only*.
- **Requirements per run**: automatic backup still enabled, a network connection (WorkManager network constraint), valid Drive authorization (silent renewal only — the background never opens a consent screen), a readable local database, and no manual backup/restore running (shared one-permit `BackupOpsLock`).
- **No repeated uploads**: the run compares the SHA-256 of the current data snapshot against the last *verified* upload; if the data is unchanged and that file is still present in `appDataFolder`, no upload happens.
- **One cloud backup kept**: a new backup is uploaded, **verified**, and only then are older backup files in the hidden app folder deleted. The previous backup is never removed before the replacement is verified.
- **Failures and retries**: temporary problems (offline, 429/quota, 5xx) are retried a bounded number of times (max 3 attempts per run) and otherwise wait for the next scheduled run; authorization problems are recorded as a status shown in Settings (the user reconnects in the app); Drive is never contacted with more permissions than `drive.appdata`.
- **Status reporting**: the card shows last success, current status (in progress / successful / up to date / waiting for network / authorization required / failed), and the next scheduled run when WorkManager can report it. No notifications are used; no notification permission is requested.

For automatic backup the Phase 6 setup above is all that is needed — no new Cloud project permissions, no additional OAuth clients and no extra Android permissions are required (WorkManager needs none).

Troubleshooting additions:

| Symptom | Likely cause |
| --- | --- |
| Status says "authorization required" although everything is set up | Token was revoked or expired; open the app and tap Connect once; the next scheduled run recovers automatically |
| Nothing happens at the preferred time | Normal: Android delays background work; watch the status line for "successful / up to date" after the delay window |
| Status "waiting for network" | The device was offline at the scheduled time; the run happens automatically once a connection is available |

## What the app stores about the connection

Only one boolean in `drive_connection.xml` (shared preferences): the user chose to connect. It is excluded from
Android backup and device transfer. No account, email, token or secret is stored, and "Connected" is shown only
after a live read-only Drive check succeeds in the current session.
