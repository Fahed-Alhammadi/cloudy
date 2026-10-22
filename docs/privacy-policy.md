---
title: Privacy Policy
---

# Privacy Policy

Last updated: October 22, 2026

This privacy policy explains how the **Cloudy** Android app (package `io.cloudy.app`) handles your information.
Cloudy is developed by Fahed Alhammadi ("we", "us"). The source code is public at
<https://github.com/Fahed-Alhammadi/cloudy>.

## In short

- Cloudy is an app for **your own Cloudy server**, which you, your company or someone you trust runs ("self-hosted").
  The app sends your data only to the server address you enter when you sign in.
- **We, the developer, do not run that server and do not receive, collect, store or sell your data.**
- The app contains **no advertising, no analytics and no tracking**, and no third-party code that collects data.

## Who is responsible for your data

Your files, photos, calendars, contacts and account live on the Cloudy server you sign in to. The person or organization that operates that server (the "server operator") decides how that data is stored, backed up and deleted, and is responsible for it. If you have questions about the data on a server, contact its operator. If you run your own server, that is you.

## What the app accesses and why

| Information | When and why | Where it goes |
|---|---|---|
| **Server address, username and password** | When you sign in. The password (and, if you use two-factor authentication, the one-time code) is sent once to your server, which returns a device token. The password is not stored on the phone. | Your server only |
| **Device token** | Kept so you stay signed in. You can see and revoke it in Cloudy's web interface (Settings → App passwords). | Stored in the app's private storage on your phone |
| **Device name** (manufacturer and model, e.g. "Samsung SM-S911B") | Sent at sign-in to label the device token, so you can recognize it in the list of signed-in devices. | Your server only |
| **Files and folders** | When you browse, open, upload, download, share, rename, move or delete files, edit text files, or mark files "available offline". Office documents are converted for preview by your server. | Your server; offline copies and temporary previews are stored in the app's private storage. When you use **Send to…**, the file goes to the app you pick (for example WhatsApp or email) |
| **Photos and videos** (permission: *Photos and videos*) | Only if you turn on **Photo backup**. The app then reads photos and videos from your gallery and uploads new ones to your server in the background. You can also pick individual photos or videos to upload. | Your server only |
| **Calendar events** | Events you view, create, edit or delete in the app. | Your server only |
| **Contacts** | Contacts you view, create, edit or delete in the app, and contacts in a file (.vcf or .csv) that **you** choose to import. The app does **not** read your phone's address book and does not request the Contacts permission. Exported contacts go only where you choose in the share sheet. | Your server only |
| **Network state** | To run photo backup and offline sync only on the network you allow (for example Wi-Fi only). | Stays on your phone |

The app does **not** access your location, camera, microphone, phone calls, SMS or phone contacts, and it does not collect advertising IDs or device identifiers.

## App updates

The app is updated by Google Play; it does not check for or download updates itself. Google's privacy policy applies to Google Play.

## Sharing of information

We do not sell, rent or share your information with anyone, and we do not have access to it. The app sends data only to:

- the Cloudy server you sign in to (see above).

When you share a file or a link, or export contacts, the information goes only to the people or apps you choose.

Your server operator may set up email so the server can send messages such as password reset links or "a file was shared with you" notices. Those emails are sent by your server, using the email address in your Cloudy profile, through the mail service the operator chooses.

## Security

- The app talks to your server over the address you enter. **Use an `https://` address**: the connection is then encrypted in transit (TLS). Plain `http://` is only meant for servers on a private home or office network.
- The device token and offline files are kept in the app's private storage, which other apps cannot read. The app opts out of Android cloud backup, so this data is not copied to a backup service.
- Your server stores passwords only as one-way hashes, and supports two-factor authentication and passkeys.

## Keeping and deleting your information

- **On your phone:** signing out removes the device token and revokes it on the server. Uninstalling the app (or *Settings → Apps → Cloudy → Clear storage*) deletes everything the app stored on the phone, including offline copies.
- **On your server:** you can delete your own files, calendars and contacts in the app or the web interface. Deleted files go to the trash first, and the server deletes them permanently after a retention period that the operator sets (30 days by default).
- **Your account:** Cloudy accounts are created by the server operator, not in the app. To delete your account and all its data, ask your server operator, who can do it under *Administration → Users*. If you run the server, you can do it yourself.

## Children

Cloudy is not directed at children under 13 and we do not knowingly collect information from children. Accounts are created and managed by the server operator.

## Changes to this policy

If this policy changes, we will update this page and the "Last updated" date above. Significant changes will also be mentioned in the app's release notes.

## Contact

Questions about this policy or the app:

- Email: [alhammadi.team@gmail.com](mailto:alhammadi.team@gmail.com)
- GitHub: <https://github.com/Fahed-Alhammadi/cloudy/issues>

For questions about the data stored on a particular Cloudy server, contact that server's operator.
