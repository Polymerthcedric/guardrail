# Android compatibility and distribution

## Compatibility contract

The app declares `minSdk = 26`, so Android 8.0 is its installation floor. It is
designed to avoid Realme-specific dependencies, but this is not certification or
runtime testing across every Android version, device manufacturer, or carrier.
The accessibility service, VPN, and any Usage Access feature require user or
system approval; users can revoke these permissions, and OEM battery policies
may affect background behavior.

The installed-app picker queries launchable apps rather than requesting
`QUERY_ALL_PACKAGES`. As a result, packages without a launcher activity are not
offered for selection. This is intentional least-privilege package visibility.

The device-specific notes in [README.md](README.md) describe behavior observed
on a realme Note 50; they are not universal Android behavior. No emulator or
additional device was available for validating the latest UI changes.

## Release recommendation

Use signed APKs from GitHub Releases for the initial distribution once production
signing is configured. The CI workflow currently produces a debug APK for
testing, not a production installer. The current build targets API 34, while
Google's current Play requirement says new apps and updates must target API 36
or higher starting August 31, 2026. Do not describe the current build as
Play-ready.

Do not raise `targetSdk` blindly before resolving the VPN foreground-service
type. `DnsVpnService` currently declares `dataSync`; Android 15 limits
`dataSync` foreground services in apps targeting API 35 or higher to six hours
per 24-hour period. That conflicts with Guardrail's intended always-on VPN
session. Choose and test the correct service type or lifecycle first, then
migrate the target SDK and test on Android 15+ devices.

Google Play's AccessibilityService policy also requires a Play Console
declaration and a prominent, separate disclosure with affirmative consent for
apps that are not accessibility tools. Guardrail is a focus-time app, not an
accessibility tool; it must not claim `isAccessibilityTool=true`. The app now
explains that it observes the foreground app, inspects visible Chrome content
only to detect Incognito mode, and keeps that accessibility information on the
device before opening Accessibility Settings. A Play submission still needs
accurate Play Console declarations and policy review.

## Sources

- [Google Play target API level requirements](https://developer.android.com/google/play/requirements/target-sdk) — API 36 requirement and effective date.
- [Google Play: Use of the AccessibilityService API](https://support.google.com/googleplay/android-developer/answer/10964491) — eligibility, disclosure, affirmative consent, and Play Console declaration.
- [Android 15 behavior changes for apps targeting Android 15 or higher](https://developer.android.com/about/versions/15/behavior-changes-15) — `dataSync` foreground-service time limit.
- [Android package visibility](https://developer.android.com/training/package-visibility) — scoped package visibility and launcher-intent queries.
