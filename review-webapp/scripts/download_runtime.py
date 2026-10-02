"""Download project-local Node and MySQL from their official distributions."""
from pathlib import Path
import hashlib
import json
import shutil
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / ".tools"
PACKAGES = [
    ("node", "node-v24.21.0-win-x64", "https://nodejs.org/dist/v24.21.0/node-v24.21.0-win-x64.zip", "node.exe"),
    ("mysql", "mysql-8.4.9-winx64", "https://cdn.mysql.com/Downloads/MySQL-8.4/mysql-8.4.9-winx64.zip", "bin/mysqld.exe"),
]

def main():
    TOOLS.mkdir(exist_ok=True)
    manifest = {}
    for name, prefix, url, executable in PACKAGES:
        if (TOOLS / name / executable).exists():
            print(f"{name}: already available", flush=True)
            continue
        archive = TOOLS / f"{name}.zip"
        if not archive.exists():
            partial = archive.with_suffix(".part")
            print(f"Downloading {name} from {url}", flush=True)
            with urllib.request.urlopen(url, timeout=60) as response, partial.open("wb") as out:
                total = 0
                last = 0
                while block := response.read(1024 * 1024):
                    out.write(block)
                    total += len(block)
                    if total - last >= 20 * 1024 * 1024:
                        print(f"{name}: {total // 1024 // 1024} MiB", flush=True)
                        last = total
            partial.replace(archive)
        digest = hashlib.file_digest(archive.open("rb"), "sha256").hexdigest()
        if name == "node":
            checksums = urllib.request.urlopen("https://nodejs.org/dist/v24.21.0/SHASUMS256.txt", timeout=60).read().decode()
            expected = next(line.split()[0] for line in checksums.splitlines() if line.endswith(prefix + ".zip"))
            if digest != expected:
                raise RuntimeError("Node archive checksum mismatch")
        destination = TOOLS / name
        destination.mkdir(exist_ok=True)
        with zipfile.ZipFile(archive) as z:
            for entry in z.infolist():
                parts = Path(entry.filename).parts
                if not parts or parts[0] != prefix:
                    raise RuntimeError(f"Unexpected archive member: {entry.filename}")
                relative = Path(*parts[1:])
                target = (destination / relative).resolve()
                if not target.is_relative_to(destination.resolve()):
                    raise RuntimeError("Unsafe archive path")
                if entry.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with z.open(entry) as src, target.open("wb") as dst:
                        shutil.copyfileobj(src, dst)
        manifest[name] = {"url": url, "sha256": digest}
        print(f"{name}: ready", flush=True)
    if manifest:
        path = TOOLS / "downloads.json"
        existing = json.loads(path.read_text()) if path.exists() else {}
        path.write_text(json.dumps(existing | manifest, indent=2), encoding="utf-8")

if __name__ == "__main__":
    main()
