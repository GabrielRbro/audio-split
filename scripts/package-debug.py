#!/usr/bin/env python3
"""Package already-built APKs with checksums and instructions. Does not sign or publish."""
from hashlib import sha256
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile
import json
import os
import shutil
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
artifacts = {
    "audio-split-0.1.0-debug.apk": root / "app/build/outputs/apk/debug/app-debug.apk",
    "tom-a-bluetooth-0.1.0-debug.apk": root / "tone-player/build/outputs/apk/bluetooth/debug/tone-player-bluetooth-debug.apk",
    "tom-b-celular-0.1.0-debug.apk": root / "tone-player/build/outputs/apk/phone/debug/tone-player-phone-debug.apk",
}
missing = [str(path.relative_to(root)) for path in artifacts.values() if not path.is_file()]
if missing:
    raise SystemExit("Compile primeiro. APKs ausentes:\n" + "\n".join(missing))

results = []
for module, location in (("core", "core/build/test-results/test"),
                         ("app", "app/build/test-results/testDebugUnitTest")):
    reports = sorted((root / location).glob("TEST-*.xml"))
    if not reports:
        raise SystemExit(f"Relatórios de testes ausentes: {module}. Execute todos os checks antes de empacotar.")
    for report in reports:
        suite = ET.parse(report).getroot()
        results.append({"module": module, "suite": suite.attrib["name"],
                        **{key: int(suite.attrib.get(key, "0"))
                           for key in ("tests", "failures", "errors", "skipped")}})
totals = {key: sum(suite[key] for suite in results)
          for key in ("tests", "failures", "errors", "skipped")}
if any(not sum(s["tests"] for s in results if s["module"] == module) for module in ("core", "app")):
    raise SystemExit("Nenhum teste foi descoberto em um dos módulos; pacote cancelado.")
if totals["failures"] or totals["errors"] or totals["skipped"]:
    raise SystemExit("Os testes precisam passar sem casos ignorados: " + json.dumps(totals))
print("TEST RESULTS: " + json.dumps(totals), flush=True)

dist = root / "dist"
bundle = dist / "audio-split-0.1.0-debug"
bundle.mkdir(parents=True, exist_ok=True)
(bundle / "TEST-RESULTS.json").write_text(json.dumps({
    "schemaVersion": 1, "commit": os.environ.get("GITHUB_SHA"),
    "totals": totals, "suites": results,
}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
checksums = []
for name, source in artifacts.items():
    target = bundle / name
    shutil.copyfile(source, target)
    checksums.append(f"{sha256(target.read_bytes()).hexdigest()}  {name}")
(bundle / "SHA256SUMS.txt").write_text("\n".join(checksums) + "\n", encoding="utf-8")
for name in ("README.md", "LICENSE", "NOTICE"):
    shutil.copyfile(root / name, bundle / name)
for document in sorted((root / "docs").glob("*.md")):
    target = bundle / "docs" / document.name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(document, target)

archive = dist / "audio-split-0.1.0-debug.zip"
with ZipFile(archive, "w", ZIP_DEFLATED) as output:
    for path in sorted(bundle.rglob("*")):
        if path.is_file():
            output.write(path, path.relative_to(dist))
print(archive)
