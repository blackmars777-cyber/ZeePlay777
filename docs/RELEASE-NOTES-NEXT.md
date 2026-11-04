# DiPlay next release notes

## Zeekr edition builds

- Zeekr integration is selected explicitly with `-Pdiplay.edition=zeekr`; standard builds exclude
  its media bridge, OEM declarations and dedicated resources. Zeekr edition uses the existing test
  package id, defaults its CarPlay car button to Zeekr's name and logo, and preserves saved custom
  names and images. The logo is sourced from vehiclespecs/brand-logos. Local standalone packaging
  verifies both explicitly provided authentication assets. See [ZEEKR-BUILD.md](ZEEKR-BUILD.md).

Changes through DiPlay 0.2.22 are documented in [0.2.22 release notes](RELEASE-NOTES-0.2.22.md). Measured checks are in [VALIDATION.md](VALIDATION.md). Device acceptance and remaining failure families are tracked in [connection reliability validation](CONNECTION_RELIABILITY.md).

## Audio and Zeekr media integration

- CarPlay realtime audio now binds exclusive UDP data/control ports. On the reported Zeekr head unit, address reuse let music, navigation or RTCP readers share a live port: navigation could interrupt music, and a music stream rebuilt after Siri could receive no packets. An Android 13 socket reproduction using the production receiver failed before the change and passed after it. Host tests cover endpoint exclusivity and music delivery across guidance and Siri replacement; the corrected APK still needs vehicle acceptance.

- The experimental Zeekr media bridge no longer reads boolean results from void OEM source/progress methods, which could interrupt publication after registration. When CoreService cannot expose the media service, it tries the exported media-center support service with interface validation and bounded fallback. Host Binder regression tests pass; target-firmware registration and source switching still need vehicle acceptance.

- Decode navigation, Siri and call Opus audio with the bundled Concentus decoder, with platform decoding as a fallback, so a head unit without a usable system Opus decoder can play voice audio. Diagnostic reports name advertised audio decoder candidates, the selected backend and received audio with no PCM output. Decoder initialization failure no longer reports audio as ready. Host regression coverage includes Android 7.1 and 12; Zeekr vehicle acceptance is pending.

- **Navigation prompts ask for audio focus** (**Settings → Advanced**), off by default: each navigation prompt asks for temporary focus that lets other sound duck, for head units that play nothing without focus ([#946](https://github.com/shihabal3amri/DiPlay/issues/946)) or where music should get quieter during prompts ([#545](https://github.com/shihabal3amri/DiPlay/issues/545)). It now works independently of **Audio focus**, so the navigation route can request focus while general media/Siri focus remains off. This allows testing the Zeekr headrest navigation path without enabling general focus; vehicle acceptance of the combined configuration is pending. [#1051](https://github.com/shihabal3amri/DiPlay/pull/1051)
