#!/usr/bin/env python3
"""Load one GitHub Actions signing secret without placing credentials in the checkout."""
import base64
import binascii
import json
import os
from pathlib import Path
import tempfile


def mask(value: str) -> None:
    # Workflow command escaping prevents a secret from injecting an extra command.
    value = value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print("::add-mask::" + value, flush=True)


def main() -> None:
    raw = os.environ.get("ANDROID_SIGNING_CONFIG", "")
    output = Path(os.environ["GITHUB_OUTPUT"])
    if not raw:
        with output.open("a", encoding="utf-8") as stream:
            stream.write("configured=false\n")
        print("::notice::Stable APK not published: configure the ANDROID_SIGNING_CONFIG Actions secret. See docs/updates.md.")
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary:
            with Path(summary).open("a", encoding="utf-8") as stream:
                stream.write("\nStable APK publication needs the `ANDROID_SIGNING_CONFIG` repository secret. [Setup instructions](https://github.com/GabrielRbro/audio-split/blob/main/docs/updates.md). Debug checks still ran.\n")
        return
    mask(raw)
    try:
        if len(raw) > 2_000_000:
            raise ValueError("oversized signing configuration")
        config = json.loads(raw)
        if type(config.get("schemaVersion")) is not int or config["schemaVersion"] != 1:
            raise ValueError("unsupported signing schema")
        names = ("keystoreBase64", "storePassword", "keyAlias", "keyPassword")
        if any(not isinstance(config.get(name), str) or not config[name] or "\n" in config[name] or "\r" in config[name] for name in names):
            raise ValueError("invalid signing field")
        for name in names:
            mask(config[name])
        key = base64.b64decode(config["keystoreBase64"], validate=True)
        if not 1024 <= len(key) <= 1_000_000:
            raise ValueError("invalid keystore size")
    except (ValueError, TypeError, AttributeError, binascii.Error):
        raise SystemExit("Invalid ANDROID_SIGNING_CONFIG; no signing values were logged.") from None

    fd, filename = tempfile.mkstemp(prefix="audio-split-release-", suffix=".p12", dir=os.environ["RUNNER_TEMP"])
    with os.fdopen(fd, "wb") as stream:
        stream.write(key)
    os.chmod(filename, 0o600)
    fields = {
        "AUDIO_SPLIT_KEYSTORE_PATH": filename,
        "AUDIO_SPLIT_STORE_PASSWORD": config["storePassword"],
        "AUDIO_SPLIT_KEY_ALIAS": config["keyAlias"],
        "AUDIO_SPLIT_KEY_PASSWORD": config["keyPassword"],
    }
    with Path(os.environ["GITHUB_ENV"]).open("a", encoding="utf-8") as stream:
        for name, value in fields.items():
            stream.write(f"{name}={value}\n")
    with output.open("a", encoding="utf-8") as stream:
        stream.write("configured=true\n")
    print("Stable release signing configured in RUNNER_TEMP.")


if __name__ == "__main__":
    main()
