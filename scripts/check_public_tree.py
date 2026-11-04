#!/usr/bin/env python3
"""Reject private research material, credentials and distribution artifacts in public source."""
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[1]
# Include new files that have not been staged yet; ignored local archives stay outside the public tree.
names = subprocess.check_output(
    ['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=root,
).decode().split('\0')
blocked_suffixes = {
    '.pk8', '.p7b', '.pem', '.key', '.p12', '.pfx', '.jks', '.keystore',
    '.apk', '.aab', '.apks', '.xapk', '.dex', '.smali', '.odex', '.vdex', '.oat',
}
blocked_directories = {'.private', 'decompiled', 'jadx-output', 'apktool-output'}
document_suffixes = {'.md', '.txt', '.rst', '.adoc', '.org', '.json', '.yaml', '.yml'}
private_block = re.compile(rb'-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----\s+[A-Za-z0-9+/=\r\n]{40,}')
private_document_reference = re.compile(
    rb'\bzeekrbackup-\d+\b|\bkrplay\b|/Users/(?!YOUR_NAME/)[^/\s`"<>]+/', re.I,
)
failures = []
for name in sorted(set(filter(None, names))):
    path = root / name
    if not path.is_file():
        continue
    parts = path.relative_to(root).parts
    if (blocked_directories.intersection(parts) or any(part.lower().startswith('zeekrbackup-') for part in parts)
            or path.suffix.lower() in blocked_suffixes):
        failures.append(f'{name}: private directory or binary artifact')
        continue
    if path.is_symlink():
        target = path.resolve()
        if not target.is_relative_to(root) or blocked_directories.intersection(target.relative_to(root).parts):
            failures.append(f'{name}: link to a private or external file')
            continue
    data = path.read_bytes()
    if private_block.search(data):
        failures.append(f'{name}: private key')
    elif path.suffix.lower() in document_suffixes and private_document_reference.search(data):
        failures.append(f'{name}: private research reference or obsolete project label')
if failures:
    raise SystemExit('Files not suitable for the public source tree:\n' + '\n'.join(failures))
print('Public tree check passed: no private artifacts, credentials or private document references.')
