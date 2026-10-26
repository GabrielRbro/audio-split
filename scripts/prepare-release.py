#!/usr/bin/env python3
"""Verify an already-signed release APK and create the app's update metadata."""
from hashlib import sha256
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
tools = sdk / "build-tools/36.0.0"
apk = root / "app/build/outputs/apk/release/app-release.apk"
if not apk.is_file():
    raise SystemExit("Signed release APK is missing; refusing to publish an unsigned/debug build.")
verification = subprocess.check_output([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)], text=True)
certificate = re.search(r"^Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)$", verification, re.MULTILINE)
expected_certificate = (root / "docs/release-certificate.sha256").read_text(encoding="utf-8").strip()
if not certificate or certificate[1].lower() != expected_certificate or "Signer #2 certificate" in verification:
    raise SystemExit("Release certificate differs from the fixed public fingerprint; publication aborted.")
badging = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(apk)], text=True)
package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.MULTILINE)
if not package or package[1] != "io.github.audiosplit":
    raise SystemExit("Unexpected APK package identity.")
if "application-debuggable" in badging:
    raise SystemExit("A debuggable APK cannot be published as a stable update.")
version_code = int(package[2])
version_name = package[3]
if version_code < 3 or not re.fullmatch(r"\d+\.\d+\.\d+", version_name):
    raise SystemExit("Invalid release version.")
asset_name = f"audio-split-{version_name}.apk"
dist = root / "dist/release"
dist.mkdir(parents=True, exist_ok=True)
target = dist / asset_name
shutil.copyfile(apk, target)
digest = sha256(target.read_bytes()).hexdigest()
metadata = {"schemaVersion": 1, "versionCode": version_code, "versionName": version_name,
            "assetName": asset_name, "sha256": digest}
(dist / "update.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
(dist / "SHA256SUMS.txt").write_text(f"{digest}  {asset_name}\n", encoding="utf-8")
(dist / "release-notes.md").write_text(
    f"Audio Split {version_name}\n\n"
    "Atualizador pelo GitHub com verificação do pacote e da assinatura antes da instalação. "
    "O Android pede confirmação para instalar.\n\n"
    "Protótipo experimental: a separação Spotify/Instagram no POCO ainda depende de validação no aparelho. "
    "Baixe apenas o APK Audio Split; os apps de tons são auxiliares de teste.\n\n"
    "Migração dos APKs debug 0.1.x: desinstale apenas o Audio Split antigo antes desta primeira instalação. "
    "Depois disso, mantenha esta assinatura para receber as próximas versões pelo aplicativo.\n",
    encoding="utf-8")
if os.environ.get("GITHUB_OUTPUT"):
    with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
        output.write(f"version={version_name}\nasset={asset_name}\ntag=v{version_name}\n")
print(f"Verified release {version_name} (versionCode {version_code}); update.json ready.")
