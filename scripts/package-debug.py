#!/usr/bin/env python3
"""Package already-built APKs with checksums and instructions. Does not sign or publish."""
from hashlib import sha256
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile
import shutil

root = Path(__file__).resolve().parents[1]
artifacts = {
    "audio-split-0.1.0-debug.apk": root / "app/build/outputs/apk/debug/app-debug.apk",
    "tom-a-bluetooth-0.1.0-debug.apk": root / "tone-player/build/outputs/apk/bluetooth/debug/tone-player-bluetooth-debug.apk",
    "tom-b-celular-0.1.0-debug.apk": root / "tone-player/build/outputs/apk/phone/debug/tone-player-phone-debug.apk",
}
missing = [str(path.relative_to(root)) for path in artifacts.values() if not path.is_file()]
if missing:
    raise SystemExit("Compile primeiro. APKs ausentes:\n" + "\n".join(missing))

dist = root / "dist"
bundle = dist / "audio-split-0.1.0-debug"
bundle.mkdir(parents=True, exist_ok=True)
checksums = []
for name, source in artifacts.items():
    target = bundle / name
    shutil.copyfile(source, target)
    checksums.append(f"{sha256(target.read_bytes()).hexdigest()}  {name}")
(bundle / "SHA256SUMS.txt").write_text("\n".join(checksums) + "\n", encoding="utf-8")
for name in ("README.md", "LICENSE", "NOTICE"):
    shutil.copyfile(root / name, bundle / name)
for name in ("compatibility.md", "test-protocol.md", "architecture.md"):
    target = bundle / "docs" / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(root / "docs" / name, target)

archive = dist / "audio-split-0.1.0-debug.zip"
with ZipFile(archive, "w", ZIP_DEFLATED) as output:
    for path in sorted(bundle.rglob("*")):
        if path.is_file():
            output.write(path, path.relative_to(dist))
print(archive)
