# ZeePlay (Zeekr edition) builds

The edition is an explicit Gradle input, independent of the checked-out branch. The default is
`standard`. Use `-Pdiplay.edition=zeekr` to include the Zeekr integration. Unknown edition names fail
during configuration. Both editions keep the existing Gradle task names, including the CI command.

## What is separated

| Location | Responsibility |
| --- | --- |
| `gradle/editions/*.properties` | Application label, CarPlay default OEM label, version suffix, debug application id suffix and debug manifest selection |
| `common/src/main/.../VehicleMediaSource.kt` | Interface used by the shared media-session lifecycle |
| `common/src/standard/` | Factory with no OEM media source |
| `common/src/zeekr/` | Zeekr Binder protocol, media source, entry service, artwork provider, manifest declarations and resources |
| `common/src/testStandard/`, `common/src/testZeekr/` | Tests compiled only for the selected edition |
| `mobile/src/zeekr/` | ZeePlay application label for debug/release; debug builds exclude upstream BYD HUD demo activities and their debug permissions |

The standard APK contains no Zeekr media implementation, discovery metadata, OEM package queries,
artwork provider or Zeekr XML resources. The Zeekr APK adds those pieces at build time. This is not
a runtime feature toggle. Shared transport fixes, software Opus decoding and the independent
navigation-focus setting remain common functionality. Existing BYD/Geely support in the upstream
core is retained in both editions.

The CarPlay return-to-car button defaults to `Zeekr` and the Zeekr logo in ZeePlay, while the standard
edition retains its `BYD` name and icons. Both the sent icon and its settings preview use the
edition's resources. Saved custom names and custom images take priority; clearing a custom image
restores the edition's packaged image. Existing saved names are not rewritten during an upgrade.
Default changes are advertised when the next CarPlay session is established.

The Zeekr logo is the unchanged `zeekr-logo.png` from
[vehiclespecs/brand-logos](https://github.com/vehiclespecs/brand-logos/blob/3f0929e70e0a9a2a502063edc4b3e5c0146cba74/zeekr-logo.png).
The selected commit, attribution and upstream license/trademark notice are packaged in the Zeekr
APK's `assets/brand-logos/` directory. The logo identifies the CarPlay vehicle button; the launcher
icon is unchanged.

The Zeekr application uses `com.shihab.diplay.hudtest` for debug and release builds.
To update an installed application in place, use a compatible signing certificate and a higher
version code. Package IDs and stored settings are not renamed by the edition selection.
Signing inputs are supplied externally as documented in [BUILD.md](BUILD.md); no signing material
belongs in the repository.

## Build a Zeekr car-test APK

Authentication input must remain outside the repository. It has this layout:

```text
/absolute/path/to/runtime-assets/
└── offline-mfi/
    ├── identity.pk8
    └── certificate.p7b
```

```sh
DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/runtime-assets bash scripts/build-zeekr.sh
```

The helper runs `:mobile:assembleStandaloneDebug` for the Zeekr edition, verifies the presence of
Zeekr code/resources/manifest declarations and exact equality of both authentication files, then
copies the result to `mobile/build/outputs/zeekr/ZeePlay-standalone-debug.apk`.
Missing or empty authentication files stop the build. No credential files are stored in Git.

For a source-only Zeekr APK, with no authentication assets, run:

```sh
env -u DIPLAY_AUTH_ASSETS_DIR ./gradlew -Pdiplay.edition=zeekr :mobile:assembleDebug
python3 scripts/verify_edition_apk.py mobile/build/outputs/apk/debug/mobile-debug.apk --edition zeekr
```

The standard source-only build uses the same tasks without the edition parameter:

```sh
env -u DIPLAY_AUTH_ASSETS_DIR ./gradlew :mobile:assembleDebug
python3 scripts/verify_edition_apk.py mobile/build/outputs/apk/debug/mobile-debug.apk --edition standard
```

The ordinary Gradle APK path is shared by editions. After switching editions, rebuild and check
the APK contents; an older file at that path is not evidence of the selected edition.

For a signed ZeePlay Release APK, supply the signing environment variables from [BUILD.md](BUILD.md)
and the runtime-assets directory, then run:

```sh
./gradlew -Pdiplay.edition=zeekr :mobile:verifyStandaloneAuthentication :mobile:lintRelease :mobile:assembleRelease
python3 scripts/verify_edition_apk.py mobile/build/outputs/apk/release/mobile-release.apk \
    --edition zeekr --auth-assets "$DIPLAY_AUTH_ASSETS_DIR" \
    --aapt2 "$ANDROID_HOME/build-tools/36.1.0/aapt2"
```

Use the installed Android build-tools version in the `aapt2` path. Release APKs shorten resource
file names; the verifier uses `aapt2` to resolve the original names through the resource table.

## Syncing upstream and validating changes

Continue merging `upstream/main` into `codex/zeekr`. Keep OEM-specific protocol and lifecycle
behavior in `common/src/zeekr/`. The shared media-session code calls the generic factory and
interface, so normal upstream changes do not need to know about Zeekr Binder contracts.
Avoid moving generic bug fixes into the edition directory.

Run the repository CI command once for the standard edition, then repeat it with
`-Pdiplay.edition=zeekr`. CI runs both editions and checks the actual APK contents. Standard tests
check that Zeekr classes are absent; Zeekr tests retain Binder, registration, fallback and stale
callback coverage. Build inputs and source-set changes invalidate the affected Gradle tasks when
switching editions. Actual firmware admission, routing and source switching still need vehicle
acceptance as described in [ZEEKR-MEDIA-SOURCE.md](ZEEKR-MEDIA-SOURCE.md).

## Standalone vehicle-data probe

Run `bash scripts/build-zeekr-vehicle-probe.sh` to build the independent `:vehicleprobe` application.
It uses `com.shihab.diplay.zeekr.vehicleprobe`, has no dependency on `:common`, `:shared` or `:mobile`,
and contains only permission requests, read-only car-data diagnostics and report export.
It has one launcher activity and one report FileProvider; no CarPlay UI, session service, USB/Bluetooth
receivers, native codecs, media bridge or authentication assets are packaged. Ordinary ZeePlay APKs
contain no probe. The former `-Pdiplay.vehicleProbe=true` mobile overlay has been removed.

The helper verifies the actual APK with `scripts/verify_vehicle_probe_apk.py` and writes
`vehicleprobe/build/outputs/standalone/zeekr-vehicle-probe-debug.apk`.
The probe has a separate package ID from the main application. In-place updates require a
compatible signing certificate and a higher version code.
See [the probe guide](ZEEKR-VEHICLE-PROBE.zh-CN.md) for installation and acceptance.

## Vehicle-data adapter

The Zeekr edition includes the observed CarSensor speed/gear/SOC adapter for one explicitly verified
model/firmware profile. Standard excludes these classes. Read permissions and optional android.car
library declarations are Zeekr-only. Percent-only iAP2 updates omit unverified range and charging.
See [vehicle-data behavior and acceptance](ZEEKR-VEHICLE-DATA.zh-CN.md) for gating, lifecycle and limitations.

## Zeekr 设置界面

Zeekr 版本采用独立的冷白／深石墨与蓝色强调色，保留深浅色切换。
车辆页顶部的「车机联动」提供媒体中心接入、播放时自动选择 CarPlay 音源，以及车机播放控制。
三项默认开启，延续旧版行为；修改后显示重新连接提示，下次会话生效，不主动断开当前连接。
关闭媒体中心接入后，自动选源和车机播放控制不生效；Siri 按键学习仍使用独立的方向盘设置。

Zeekr 设置、搜索和首次向导不显示 BYD／DiLink 导航、仪表投屏、ADB 车辆探测、氛围灯或专用摇杆设置。
车机热点仍放在连接分类，使用现有极氪车机服务；原生车辆数据放在高级分类，继续受车型／固件准入限制。
界面主机截图保存在 `common/build/reports/zeekr-settings-ui/`，包括紧凑、完整、大字体、浅色和阿拉伯语场景。
车机媒体服务兼容性、方向盘事件和 iPhone 音源切换仍需实车验证。

## ZeePlay branding

The Zeekr edition uses the ZeePlay application label and supplied green artwork in
`common/src/zeekr/res/drawable-xxxhdpi/zeeplay_icon.png`. The launcher, home header
and in-app brand icons share `ic_carplay`; the notification status icon remains monochrome.
The supplied image is stored unchanged; Android handles density scaling. Zeekr Release PNG
crunching is disabled so packaging preserves the supplied artwork bytes and transparent pixels.
The mobile resource overlay links to this same source image, avoiding the library Release
resource compiler's PNG rewriting without keeping a second copy of the artwork.

Run `python3 scripts/sync_zeekr_branding.py` after changing shared product copy.
Its generated locale overlays preserve standard-edition copy and protect repository
URLs and `Downloads/DiPlay` storage paths. CI checks that these overlays are current.
Package IDs, saved preferences, backup format and pairing identifiers stay compatible
with the existing Zeekr edition. The separate vehicle probe keeps its existing artifact filenames.
