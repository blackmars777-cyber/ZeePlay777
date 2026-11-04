# CarPlay · Zeekr OS 7.2.5 edition

[English](README.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-TW.md)

**A CarPlay app customized for Zeekr OS 7.2.5 head units**, with wired and wireless connection, a Zeekr-style interface, and integration with the vehicle media center, hotspot and supported vehicle-data services.

Forked from [DiPlay](https://github.com/shihabal3amri/DiPlay).

## What this fork changes

| Area | Zeekr customization |
| --- | --- |
| Interface | Light and dark themes, restrained blue accents, line icons and grouped settings inspired by the Zeekr OS 7 design language; adaptive landscape, split-screen and large-text layouts. |
| Settings | Zeekr-focused pages, search and onboarding; BYD / DiLink configuration is hidden. |
| Media center | CarPlay song information, artwork and progress; playback commands from supported vehicle controls; configurable automatic CarPlay source selection. |
| Car hotspot | Prefer the detected OEM hotspot service, reusing the saved car configuration. That service path does not require ADB. |
| Vehicle data | Read measured battery percentage, dynamic range, speed and gear through the supported CarSensor profile; optional reporting to iPhone. |
| Configuration | Separate controls for media integration, source selection and playback commands; changes that require a new session show a reconnect prompt. |

## Zeekr OS 7.2.5 adaptation

**Zeekr OS 7.2.5 is the target system for this fork.** The app uses the actual available window and system insets; it does not draw a replica system status bar, climate dock or vehicle controls, and does not restyle the projected CarPlay content.

The OS version alone does not establish compatibility with every model or firmware. Media, hotspot and vehicle-data integration depend on the services and permissions available on the car. Vehicle-data decoding remains restricted to the explicitly matched model/firmware profile; missing or unverified readings are not fabricated. See the [vehicle-data scope](docs/ZEEKR-VEHICLE-DATA.zh-CN.md).

## Getting started

1. Install the **Zeekr edition on the head unit**, not on the iPhone. The package is `com.shihab.diplay.hudtest`.
2. Choose USB, the car hotspot, Wi-Fi Direct or an existing Wi-Fi network, then complete pairing and the requested permissions.
3. Open **Settings → Vehicle → Vehicle integration** to configure the media center, automatic source selection and playback controls.
4. Enable optional battery/range and speed reporting under **Advanced** only when the vehicle data is readable. Speed reporting also requires location sharing.

Use a matching signing certificate when updating an existing installation to preserve its settings and pairing data. The standalone vehicle-data probe is a separate app.

## Build the Zeekr edition

```sh
./gradlew -Pdiplay.edition=zeekr :mobile:assembleDebug
```

Output: `mobile/build/outputs/apk/debug/mobile-debug.apk`.

Ordinary source/CI builds omit CarPlay authentication assets. For a complete local build, provide the external runtime assets as described in the [Zeekr build guide](docs/ZEEKR-BUILD.md); do not put credentials or signing keys in the repository.

## Documentation and feedback

- [Zeekr builds and packaging](docs/ZEEKR-BUILD.md)
- [Media-center integration](docs/ZEEKR-MEDIA-SOURCE.md)
- [OEM hotspot service](docs/ZEEKR-HOTSPOT.md)
- [Vehicle data and acceptance](docs/ZEEKR-VEHICLE-DATA.zh-CN.md)
- [Interface and component specification](docs/design/ZEEKR-OS72-HMI.md)

For a connection or integration issue, export a report from **Settings → Diagnostics** and include the vehicle model, full OS/firmware version, iPhone/iOS version, connection mode, reproduction steps and failure time. Review the report before sharing it.

## Source and licenses

This fork retains DiPlay's existing open-source license and attribution notices. See [LICENSE](LICENSE) and [third-party notices](docs/THIRD_PARTY_NOTICES.md). This is an independent fork, with no official affiliation with Zeekr or Apple.
