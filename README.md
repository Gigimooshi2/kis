# Kis (כיס)

Android app: a daily allowance (default ₪70) is added to your balance every day at a set hour,
and Google Pay / Google Wallet payment notifications are subtracted automatically.

- **Stacking** (on by default): unused budget carries over. Off = leftovers are dropped each day
  (overspending still carries as debt).
- **Home-screen widget** with the balance.
- Manual expenses, balance correction, long-press to delete any entry (reverses it).
- **Discovery mode** (Settings): logs every notification that contains a ₪ amount, plus tracked-app
  notifications the parser couldn't read. Use it if payments aren't being picked up.

## Updates

Kis updates itself from this repo's GitHub releases (same approach as GigiKav):
- Checks every ~4 hours and when opened, downloads new builds quietly.
- The **first** update needs a tap on the blue banner (and allowing "Install unknown apps" for Kis).
- After that, on Android 12+, updates install silently while Kis is closed.
- Toggle / manual check: Settings → Auto-update.
- Requires the repo to be **public** (the app reads `github.com/Gigimooshi2/kis/releases/latest` without a token).

## Build

Push to `main` → GitHub Actions builds the APK and publishes release `v1.0.<run number>`:

    https://github.com/Gigimooshi2/kis/releases/latest/download/kis.apk

(Also attached to each run as an artifact.) Manual trigger: Actions → Build APK → Run workflow.

### Keep the same signing key (do this once)

Without it, every build is signed with a random key and Android refuses to update over the
old install, so you'd have to uninstall (losing balance + history).

Repo → Settings → Secrets and variables → Actions → New repository secret:
- Name: `DEBUG_KEYSTORE_B64`
- Value: contents of `keystore-b64.txt` (base64 of a debug keystore, password `android`)

Don't commit the keystore itself.

## First run on the phone

1. Install the APK, open it, tap the red banner → enable notification access.
2. Android 13+: if the toggle is greyed out ("Restricted setting"), go to
   App info → ⋮ → **Allow restricted settings**, then enable it.
3. Settings → set daily amount / hour / stacking.

## How amounts are read

`AmountParser.kt` looks for `₪12.90`, `12.90 ₪`, `ILS 12.90`, `12,90 ש"ח` etc. in the notification
title/text (RTL marks stripped). Watched apps default to Google Wallet
(`com.google.android.apps.walletnfcrel`) and Play Services (`com.google.android.gms`); editable in Settings.
Text with "declined/failed/נדחה" is ignored; "refund/זיכוי/החזר" is added back.
The same notification re-posted within 5 minutes with the same amount is counted once.
