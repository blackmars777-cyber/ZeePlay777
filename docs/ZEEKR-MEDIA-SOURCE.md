# Zeekr / ECARX media source bridge

This bridge is compiled only with `-Pdiplay.edition=zeekr`. The default standard edition excludes
its code, manifest declarations and resources. See [ZEEKR-BUILD.md](ZEEKR-BUILD.md) for configuration
and car-test packaging.

DiPlay declares one media source under the installed app's real package name. While CarPlay is
connected, a head unit with `com.zeekr.coreservice.CoreService` is offered the iPhone's now-playing
information and transport controls. A missing service or a rejected registration does not stop the
existing Android MediaSession or CarPlay audio. This is an experimental integration pending vehicle
acceptance, not a claim of support for every Zeekr or ECARX firmware. BYD DiLink does not use this bridge.

## Implemented behavior

- Manifest discovery: `ZeekrMediaClientSupport`, `zeekr_media_config.xml`, and `InFloatingSource`.
- CoreService connection and media-service readiness subscription. Package registration uses the
  installed package, with the SDK's `:userId` suffix for secondary Android users.
- Readiness and disconnection reason codes are recorded. Temporary service disconnect/init-failure/
  service-death codes (304/312/317) retry initialization after two seconds, with at most three attempts
  per startup failure episode. A missing readiness callback times out after five seconds. Explicit
  unavailable/not-found responses do not enter a retry loop. Connection end cancels pending work.
- If CoreService is missing, explicitly reports media unavailable (305), or exhausts the bounded
  readiness retries, try the exported `com.zeekr.mediacenter/ecarx.xsf.mediacenter.ZeekrMediaCenterService`
  once per CarPlay session using `ecarx.xsf.ZEEKR_MEDIA_CENTER_SERVICE`. Verify its Binder descriptor
  is `IZeekrSupportService` before any protocol request. This endpoint uses transaction 3 instead
  of CoreService transaction 6; the routing envelope is otherwise unchanged. Permission or registration
  rejection is reported without impersonating another package or retrying indefinitely. A successful
  direct connection retires the old CoreService listener; later CoreService disconnects cannot clear
  the direct registration. The direct Android binding can reconnect after service disconnection.
- Runtime `mediaCenterRegisterMusicNew` registration, extension selection callbacks, and retrieval
  of `IMediaCenterSvc`. Registration requires both the returned client token and the service Binder.
- Source-type declaration (`6`, matching the observed client), title, artist, album, duration,
  position, playing/paused state, derived track identity, and optional cover URI. Vendor playback
  states are `1` playing and `0` paused, not Android's PlaybackState values.
- Play, pause, next and previous are forwarded as explicit CarPlay HID commands. Selecting DiPlay
  requests playback through the iPhone; the published state still comes from the iPhone.
- An explicit media-center focus switch to another package pauses iPhone music. Progress updates
  do not reclaim the source; explicit selection or a subsequent phone playback transition can.
- Media-center restart invalidates old clients and registers again on its readiness notification.
  CarPlay disconnect immediately disables controls, clears playback information, unregisters the
  token, removes the CoreService listener, revokes cover grants, and removes this session's files.
- JPEG covers are written on a worker, exposed through a non-exported FileProvider limited to
  `cache/zeekr-artwork`, and granted to the UID requesting the cover through the supplied Binder.
  At most two successfully published cover files are retained per session. Position updates do not
  rewrite covers or republish track metadata. A one-second progress tick extrapolates from the
  latest phone position while playing, clamps to track duration, and stops while paused/disconnected.
  All vendor RPC and image compression run off the UI
  and PCM threads; pending publication is coalesced to the newest snapshot.
- The declared service supports the SDK's `operation` / `operationData` selection Intents and
  bound extension endpoint. It handles a request and stops; the existing DiPlay session service
  continues to own the connection. A request with no connected phone returns failure.

Only main-display controls are accepted. Lyrics, collection, download, loop-mode switching,
seeking, playlists, recommendations, independent passenger/rear playback, and automatic cold
reconnection/recovery are not advertised. No source-switcher popup is added to DiPlay. The SDK's
legacy global broadcast/VR declaration and recovery-intent registration are not needed for the
implemented transport subset and are not included.

## Compatibility and audio behavior

The bridge validates service interfaces and reply types, bounds initialization retries, and rejects
unknown transactions. A source registration callback is not proof that the vehicle has selected
that source. Actual package admission, permissions, source-list inclusion and arbitration depend
on the installed firmware and must be checked on the target vehicle.

Navigation prompt focus is configurable independently from general audio focus. Stream selection
and focus preferences are not changed automatically; apply settings explicitly and reconnect when
requested. AudioTrack progress alone does not establish that sound reaches the intended speakers.

Realtime audio endpoints use exclusive UDP bindings so music, guidance and control readers cannot
share an active endpoint. Tests cover stream replacement and concurrent guidance. Media registration,
source switching, calls, Siri and output routing still require actual vehicle acceptance.

## Validation

Host tests in `ZeekrMediaProtocolTest` and `ZeekrMediaSourceTest` use real Parcel/Binder contracts
with a simulated CoreService/media center. They check envelope encoding, explicit transport keys,
source/display filtering, vendor playback states, metadata/progress separation, switch-away
behavior, token cleanup, stale callbacks, service restart, and missing/denied service handling.
They do not establish actual OEM acceptance.

Run the full command from `AGENTS.md` before delivering a build.

Vehicle acceptance:

1. Install the debug APK and connect CarPlay. Check `adb logcat -s DiPlay-ZeekrMedia` for
   `source registered`, or include DiPlay's diagnostic report, whose wheel/media log records
   bounded registration/control messages without track text or cover bytes.
2. Verify DiPlay appears in the car's source list, shows the actual phone title/artist/cover,
   and updates play/pause and position. Registration acceptance alone does not prove list inclusion.
3. Test play, pause, next and previous from the system card. Switch to another source and verify
   iPhone music pauses. Switch back and verify it resumes without dropping the CarPlay connection.
4. Verify phone calls, Siri and navigation still behave correctly. The new bridge sends a music
   pause on explicit source changes; it does not close or globally mute the CarPlay audio sink.
5. Disconnect the phone. Check the old title and cover clear, controls fail instead of claiming
   playback, and no CarPlay audio restarts. Reconnect and repeat after a media-center restart.
6. Repeat on an ordinary Android/BYD device: no CoreService bind, source arbitration, or changes
   to the existing standard MediaSession behavior should occur.
