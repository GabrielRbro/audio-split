#!/usr/bin/env python3
"""Upload the complete release as a draft, then make it visible to the updater."""
import json
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
dist = root / "dist/release"
metadata = json.loads((dist / "update.json").read_text(encoding="utf-8"))
tag = "v" + metadata["versionName"]
repository = "GabrielRbro/audio-split"
if os.environ.get("GITHUB_REPOSITORY") != repository:
    raise SystemExit("Release publication is restricted to GabrielRbro/audio-split.")

existing = subprocess.run(["gh", "api", f"repos/{repository}/releases/tags/{tag}"], capture_output=True, text=True)
if existing.returncode == 0:
    release = json.loads(existing.stdout)
    if not release["draft"]:
        print(f"{tag} is already published; its assets and signature were preserved. Bump versionCode/versionName for a new release.")
        raise SystemExit(0)
    subprocess.run(["gh", "release", "edit", tag, "--repo", repository, "--target", os.environ["GITHUB_SHA"]], check=True)
elif "404" in existing.stderr:
    subprocess.run(["gh", "release", "create", tag, "--repo", repository, "--draft", "--target", os.environ["GITHUB_SHA"],
                    "--title", f"Audio Split {metadata['versionName']}", "--notes-file", str(dist / "release-notes.md")], check=True)
else:
    raise SystemExit("Could not inspect release state; publication aborted.")

subprocess.run(["gh", "release", "upload", tag, "--repo", repository, "--clobber",
                str(dist / metadata["assetName"]), str(dist / "update.json"), str(dist / "SHA256SUMS.txt")], check=True)
subprocess.run(["gh", "release", "edit", tag, "--repo", repository, "--draft=false", "--prerelease=false", "--latest"], check=True)
print(f"Published complete release https://github.com/{repository}/releases/tag/{tag}")
