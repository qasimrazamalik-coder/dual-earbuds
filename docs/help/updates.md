# Updates and versions
<!-- keywords: update, upgrade, new version, version, install update, download, check for updates, github, release, apk update, naya version, update karna, purana version -->

## How do I update the Windows app?
Open Settings > Updates and press **Check now**; when a new version is found, press **Install update**. Dual Earbuds downloads the installer, closes, updates and opens again, and your settings stay as they are.
1. Settings > **Updates** > **Check now**.
2. Read what's new, then press **Install update** (or **Release page** to see it on GitHub).
3. Wait while it downloads; the app restarts by itself.
**Windows:** nothing is downloaded or installed until you press Install.

## How do I update the Android app?
Open Settings, tap **Check for updates**, then **Download** and **Install**. Every release is signed with the same key, so it installs over the old version and keeps your settings.
1. Settings > **Check for updates**.
2. Tap **Download** (or **What's new** to read the release notes).
3. Tap **Install**.
**Android:** the first time, Android may ask you to allow installs from this app: tap **Open setting**, turn on **Allow from this source**, then come back.

## Does Dual Earbuds update by itself?
No. With **Check once a day** on, the app only asks GitHub whether a new version exists and tells you; it never downloads or installs without your tap.
**Windows:** Settings > Updates > **Check once a day**. **Android:** Settings > **Check once a day**.

## Where do updates come from?
Updates come only from the project's GitHub releases, over HTTPS: DualEarbuds-Setup.exe for Windows and DualEarbuds.apk for Android. The app refuses downloads from any other address.

## How do I see which version I have?
Both apps show their version number.
**Windows:** the **About** page and Settings > Updates show the version, for example "Dual Earbuds 3.3.0". **Android:** the version is shown at the top of Settings, for example "v3.3.0".

## Do the laptop and the phones need the same version?
No, but keeping them current is best. Hosts and listeners of different versions work together because each side ignores what it doesn't know; newer features such as UDP audio and clock sync need both sides to be recent.

## Why does the update check fail?
The check needs the internet and GitHub to answer. Messages such as "Couldn't reach GitHub" or "GitHub is busy right now" mean try again later; the app keeps working offline either way.

## How do I uninstall Dual Earbuds?
Uninstall it like any other app on that device.
**Windows:** use Windows Settings > Apps > Installed apps > Dual Earbuds > Uninstall; running DualEarbuds-Setup.exe again also offers Repair, Modify and Uninstall. **Android:** long-press the app icon > App info > Uninstall.
