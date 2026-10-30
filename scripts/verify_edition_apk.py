#!/usr/bin/env python3
"""Check the built APK, including absence of Zeekr code in the standard edition."""
import argparse
from pathlib import Path
import re
import subprocess
import zipfile


def require(condition, detail):
    if not condition:
        raise SystemExit(f"APK verification failed: {detail}")


def verify(apk_path, edition, auth_assets=None, aapt2=None):
    # Release packaging shortens file paths. Resolve the resource table rather than assuming
    # that res/xml/name.xml and res/raw/name.png survive unchanged in the ZIP directory.
    resource_files = {}
    if aapt2:
        table = subprocess.check_output([str(aapt2), "dump", "resources", str(apk_path)], text=True)
        current = None
        for line in table.splitlines():
            resource = re.match(r"\s+resource 0x[0-9a-fA-F]+ (\S+)", line)
            if resource:
                current = resource.group(1)
            file_value = re.match(r"\s+\(\) \(file\) (\S+)", line)
            if current and file_value:
                resource_files[current] = file_value.group(1)
    with zipfile.ZipFile(apk_path) as apk:
        names = set(apk.namelist())
        zeekr = edition == "zeekr"
        def resource_path(kind, name, extension):
            original = f"res/{kind}/{name}.{extension}"
            return original if original in names else resource_files.get(f"{kind}/{name}")
        for resource in ("zeekr_media_config", "zeekr_artwork_paths"):
            require((resource_path("xml", resource, "xml") in names) == zeekr, resource)
        resource_root = Path(__file__).resolve().parent.parent / "common/src" / ("zeekr" if zeekr else "main") / "res/raw"
        for resource in ("ic_car_home.png", "placeholder_icon.png"):
            path = resource_path("raw", Path(resource).stem, "png")
            require(path in names, f"{resource}: use --aapt2 for optimized APKs")
            require(apk.read(path) == (resource_root / resource).read_bytes(), resource)
        require(("assets/brand-logos/NOTICE.txt" in names) == zeekr, "logo attribution")
        dex = b"".join(apk.read(name) for name in names if name.endswith(".dex"))
        for cls in ("ZeekrMediaSource", "ZeekrMediaIntentService", "ZeekrArtworkProvider"):
            descriptor = f"Lcom/shilapi/xcertplay/{cls};".encode()
            require((descriptor in dex) == zeekr, cls)
        manifest = apk.read("AndroidManifest.xml")
        for declaration in ("ZeekrMediaClientSupport", "com.zeekr.coreservice", "com.zeekr.mediacenter"):
            present = any(declaration.encode(encoding) in manifest for encoding in ("utf-8", "utf-16le"))
            require(present == zeekr, declaration)
        if zeekr:
            require(any("Zeekr".encode(encoding) in manifest for encoding in ("utf-8", "utf-16le")),
                    "Zeekr application label")
            require(b"Lcom/shilapi/xcertplay/hud/StandaloneHudDemoActivity;" not in dex, "BYD debug demo")
        expected_auth = {"assets/offline-mfi/identity.pk8", "assets/offline-mfi/certificate.p7b"}
        bundled_auth = {name for name in names if name.startswith("assets/offline-mfi/") and not name.endswith("/")}
        if auth_assets:
            require(bundled_auth == expected_auth, "unexpected or missing authentication assets")
            for name in expected_auth:
                expected = (Path(auth_assets) / name.removeprefix("assets/")).read_bytes()
                require(expected and apk.read(name) == expected, "authentication asset mismatch")
        else:
            require(not bundled_auth, "source-only APK contains authentication assets")
    print(f"Verified {edition} APK ({'standalone' if auth_assets else 'source-only'}): {apk_path}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--edition", required=True, choices=("standard", "zeekr"))
    parser.add_argument("--auth-assets", type=Path)
    parser.add_argument("--aapt2", type=Path, help="Android SDK aapt2 executable; required for optimized Release resources")
    args = parser.parse_args()
    verify(args.apk, args.edition, args.auth_assets, args.aapt2)
