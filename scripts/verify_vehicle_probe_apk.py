#!/usr/bin/env python3
"""Require the actual APK to contain only standalone probe functionality."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

PACKAGE = "com.shihab.diplay.zeekr.vehicleprobe"


def require(condition, detail):
    if not condition:
        raise SystemExit(f"Probe APK verification failed: {detail}")


def find_aapt2(explicit=None):
    if explicit:
        return str(explicit)
    candidates = []
    for sdk in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
                str(Path.home() / "Library/Android/sdk"), str(Path.home() / "Android/Sdk")):
        if sdk:
            candidates.extend(Path(sdk).glob("build-tools/*/aapt2"))
    if candidates:
        return str(sorted(candidates)[-1])
    found = shutil.which("aapt2")
    require(found, "aapt2 not found; pass --aapt2 or set ANDROID_HOME")
    return found


def parse_tree(text):
    root = {"kind": "root", "attrs": {}, "children": []}
    stack = [(-1, root)]
    for line in text.splitlines():
        element = re.match(r"(\s*)E: (\S+)", line)
        if element:
            indent = len(element.group(1))
            while stack[-1][0] >= indent:
                stack.pop()
            node = {"kind": element.group(2), "attrs": {}, "children": []}
            stack[-1][1]["children"].append(node)
            stack.append((indent, node))
        elif line.lstrip().startswith("A:"):
            key, value = line.strip()[3:].split("=", 1)
            key = re.sub(r"\(0x[0-9a-f]+\)$", "", key).rsplit(":", 1)[-1]
            quoted = re.match(r'"([^"]*)"', value)
            stack[-1][1]["attrs"][key] = quoted.group(1) if quoted else value
    return root["children"][0]


def children(node, kind):
    return [child for child in node["children"] if child["kind"] == kind]


def verify(apk_path, aapt2=None):
    with zipfile.ZipFile(apk_path) as apk:
        names = apk.namelist()
        require(not any(name.startswith("lib/") for name in names), "native libraries are forbidden")
        require(not any(name.startswith("assets/") for name in names), "bundled assets are forbidden")
        dex = b"".join(apk.read(name) for name in names if name.endswith(".dex"))
        require(f"L{PACKAGE.replace('.', '/')}/ZeekrVehicleProbeActivity;".encode() in dex, "probe activity missing")
        for prefix in (b"Lcom/shilapi/xcertplay/", b"Landroidx/compose/", b"Landroidx/media3/",
                       b"Lcom/google/zxing/", b"Lorg/bouncycastle/"):
            require(prefix not in dex, f"unrelated implementation present: {prefix.decode()}")
    xml = subprocess.check_output([find_aapt2(aapt2), "dump", "xmltree", "--file",
                                   "AndroidManifest.xml", str(apk_path)], text=True)
    manifest = parse_tree(xml)
    require(manifest["attrs"].get("package") == PACKAGE, "wrong application id")
    require(not children(manifest, "queries"), "OEM/DiPlay package discovery is forbidden")
    permissions = {node["attrs"]["name"] for node in children(manifest, "uses-permission")}
    expected = {"android.car.permission." + name for name in ("CAR_SPEED", "CAR_POWERTRAIN", "CAR_ENERGY", "CAR_INFO")}
    # AndroidX Core may declare its own signature-protected receiver permission.
    permissions.discard(PACKAGE + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    require(permissions == expected, f"unexpected permissions: {permissions ^ expected}")
    apps = children(manifest, "application")
    require(len(apps) == 1, "expected one application")
    app = apps[0]
    require(app["attrs"].get("allowBackup") == "false", "backup must be disabled")
    activities = children(app, "activity")
    require(len(activities) == 1 and activities[0]["attrs"].get("name") == PACKAGE + ".ZeekrVehicleProbeActivity",
            "expected exactly one probe activity")
    require(activities[0]["attrs"].get("exported") == "true", "probe launcher must be exported")
    filters = children(activities[0], "intent-filter")
    require(len(filters) == 1, "expected one launcher filter")
    require([n["attrs"]["name"] for n in children(filters[0], "action")] == ["android.intent.action.MAIN"],
            "unexpected activity action")
    require([n["attrs"]["name"] for n in children(filters[0], "category")] == ["android.intent.category.LAUNCHER"],
            "unexpected activity category")
    for kind in ("activity-alias", "service", "receiver"):
        require(not children(app, kind), f"{kind} components are forbidden")
    providers = children(app, "provider")
    require(len(providers) == 1, "expected only the report provider")
    provider = providers[0]["attrs"]
    require(provider.get("name") == "androidx.core.content.FileProvider", "unexpected provider")
    require(provider.get("authorities") == PACKAGE + ".vehicle-probe", "wrong report authority")
    require(provider.get("exported") == "false" and provider.get("grantUriPermissions") == "true",
            "report provider must use explicit URI grants")
    libraries = children(app, "uses-library")
    require(len(libraries) == 1 and libraries[0]["attrs"].get("name") == "android.car" and
            libraries[0]["attrs"].get("required") == "false", "car library must be optional")
    print(f"Verified probe-only APK: {apk_path} ({apk_path.stat().st_size:,} bytes); "
          "one activity, one report provider, no DiPlay code/assets/native libraries/services")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--aapt2", type=Path)
    args = parser.parse_args()
    verify(args.apk, args.aapt2)
