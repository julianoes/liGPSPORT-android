# Strava upload

Sends a recorded activity straight from the BSC200 to Strava, without
a computer in the middle.

Strava's Android app cannot receive a shared FIT file — its only
`ACTION_SEND` filters are `text/plain` and `image/*` — so the share
sheet is a dead end for this and the v3 API is the only route.

## Why you have to register your own API application

The upload API needs OAuth credentials, and Strava requires
`client_secret` for the token exchange with no PKCE alternative. In a
native app the secret can only live inside the APK, where anyone can
extract it. That's acceptable for a build you install on your own
phone, and unacceptable for one you hand out — so this repo ships no
credentials and expects you to bring your own.

## Setup

1. Go to <https://www.strava.com/settings/api> and create an
   application.
   - **Authorization Callback Domain**: `localhost` — this must match
     the `ligpsport://localhost` redirect in
     `strava/StravaAuth.kt`. Anything else and Strava rejects the
     redirect.
   - Category, website and icon are cosmetic. The app's own launcher
     icon works fine if you want the consent screen to look right.
2. Copy the credentials into a gitignored properties file:

   ```sh
   cp app/strava.properties.example app/strava.properties
   # then fill in:
   #   client_id=12345
   #   client_secret=…
   ```

   `LIGPSPORT_STRAVA_CLIENT_ID` / `LIGPSPORT_STRAVA_CLIENT_SECRET` env
   vars work too, for CI. With neither set, the Strava UI hides itself
   rather than offering a button that can only fail.
3. Rebuild and install, then **Settings → Strava → Connect to Strava**.
   The consent screen opens in the Strava app when it's installed and
   in the browser otherwise; both land back on
   `StravaAuthActivity`, which does the token exchange.
4. **Settings → Activities on device** now shows a cloud-upload button
   on each row.

Only the `activity:write` scope is requested. If you untick the upload
permission on the consent screen, the app rejects the callback rather
than storing a token that can't upload.

## Visibility: what the API can and can't do

Uploads are muted from followers' feeds (`hide_from_home` on
`PUT /activities/{id}`), so they raise no notifications.

**This is not the same as private.** Strava's three-tier visibility
(Everyone / Followers / Only You) is not settable through the API at
all — the old `private` upload flag is defunct and silently ignored. A
muted activity is still reachable at its URL and in your profile,
according to your account's default activity visibility.

If you want uploads to be genuinely private, set the account default
once, on Strava: **Settings → Privacy Controls → Activities → Only
You**. Uploads inherit it. Note it does not apply retroactively.

## How an upload flows

```
Settings → Activities on device → ☁ button
   │
   ├─→ FIT already downloaded?  → reuse it
   │   else UploadPipeline.downloadActivity (BLE, tens of seconds)
   ├─→ access token expired?    → StravaClient.refresh (rotates the
   │                               refresh token; persist the response)
   ├─→ POST /api/v3/uploads     (multipart, data_type=fit,
   │                             external_id=<filename>)
   ├─→ poll GET /api/v3/uploads/:id every 1.5 s until `activity_id`
   │   or `error` (Strava's mean processing time is under 2 s;
   │   we give up waiting after 90 s and report "still processing")
   └─→ PUT /api/v3/activities/:id  (hide_from_home=true)
```

`external_id` is the FIT filename, which is derived from the activity
timestamp and therefore stable. That's the duplicate guard: uploading
the same ride twice gets rejected by Strava instead of creating a
twin.

## Tokens

Access tokens last six hours; the refresh token is long-lived and
stored in app-private `SharedPreferences` (`ligpsport.strava`), the
same treatment the paired device MAC gets. No encryption — assume a
rooted or shared phone can read it.

**Settings → Strava → Disconnect** only forgets the token locally. To
revoke the grant itself, use <https://www.strava.com/settings/apps>.

## Rate limits

Strava's default is 100 requests per 15 minutes and 1000 per day. One
upload costs roughly 3–5 requests (upload, a few polls, the mute), so
the limit isn't reachable in normal use.
