# SmugView Privacy Policy

_Last updated: October 31, 2026_

SmugView ("the app", on Android, iPhone and iPad) is an independent viewer for public [SmugMug](https://www.smugmug.com) photography sites. It is not affiliated with, endorsed by, or sponsored by SmugMug, Inc.

## Data the app collects

SmugView does not require an account, sign-in, or registration, and does not collect, transmit, or sell any personal information to the developer or any third party.

**Data that stays only on your device:**

* **Gallery passwords** — if you unlock a password-protected SmugMug gallery, the password is encrypted and stored locally on your device (using Android's `EncryptedSharedPreferences`, or the Keychain on iPhone and iPad) so you don't have to re-enter it. It is never transmitted anywhere except to SmugMug itself, to unlock that gallery.
* **Browsing cache** — folder, gallery, and photo listings you've viewed are cached locally in an on-device database to make browsing faster. This cache is not backed up or synced off-device.
* **Custom collections** — any collections you create, and any photos you choose to save for offline viewing, are stored locally on your device.
* **Photos you download** — when you choose to download a photo, it is saved to the `Pictures/SmugView` folder on your device (Android) or to your Photos library (iPhone and iPad, which asks for add-only permission), where your other apps can see it. SmugView does not upload it anywhere.

**Data sent to SmugMug:** to display content, the app sends requests directly to SmugMug's public API (`api.smugmug.com`) — for example, the site nickname or search terms you enter, and the password for a gallery you're unlocking. This is the same publicly documented API used to browse SmugMug in a web browser. Please refer to [SmugMug's own privacy policy](https://www.smugmug.com/legal/privacy) for how SmugMug handles that traffic.

**What you share:** when you share a photo link, the link is a SmugMug web page and nothing else is added. When you share a picture, SmugView removes camera details (such as serial numbers and the camera owner's name) and location from the picture before it is handed to the app you choose. What that app does with it is up to that app.

## Data the app does not collect

SmugView itself contains no advertising SDK, no analytics SDK, and no crash-reporting SDK (the one exception is the Google Cast SDK on iPhone and iPad, described under Casting). It does not use an advertising ID, does not track you across apps or websites, and does not share data with data brokers or advertisers.

## Permissions

* **Internet / network state / Wi-Fi state** — required to reach the SmugMug API and to cast photos to devices on your local network (Chromecast, Roku, Fire TV).
* **Storage (legacy, Android 8 and 9 only)** — asked for only when you choose to download a photo, to save it to `Pictures/SmugView`. Newer Android versions need no permission for this.

## Notifications and background checks

If you follow a site, SmugView can check it in the background for new galleries (a periodic request to SmugMug) and show a notification on your device. Notifications are created on your device; nothing is sent to a push service, and you can turn them off in your device settings. The check also runs when you open the app.

## Casting

When you cast to a TV device, SmugView runs a small local web server on your phone, reachable only by the specific device you cast to on your local Wi-Fi network, to serve the photo being displayed. This traffic never leaves your local network.

**On iPhone and iPad** SmugView uses Google's Cast SDK to find Chromecast and other Google Cast devices on your Wi-Fi network (and, if you allow it, nearby by Bluetooth), which is why iOS asks for local network permission the first time you open the cast sheet. Photo links are sent to the device you choose. The Cast SDK, which is Google's code, collects a device identifier, diagnostic data and product-interaction data for analytics and app functionality; Google states this data is not linked to you and not used for tracking. See [Google's privacy policy](https://policies.google.com/privacy). AirPlay to a TV or display uses Apple's own system and sends nothing to us.

## Children's privacy

SmugView is not directed at children and does not knowingly collect information from children. Since SmugView can display any public SmugMug gallery, and gallery content is controlled entirely by third-party SmugMug site owners, parents should supervise use by children as they would with any general-purpose photo browser.

## Changes to this policy

If this policy changes, the updated version will be posted at this same location with a revised "Last updated" date.

## Contact

Questions about this policy can be sent to: kidzi+smugview@idzifamily.com. Support: [SUPPORT](SUPPORT).
