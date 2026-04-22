"""Batch 3 mod install for NeoForge 1.21.1."""

import json, sys, urllib.request, urllib.parse
from pathlib import Path

MODS_DIR = Path(r"C:\Users\ScottSingleton\AppData\Roaming\PrismLauncher\instances\OC2 Test platform\minecraft\mods")
GAME_VERSION = "1.21.1"
LOADER = "neoforge"
API = "https://api.modrinth.com/v2"
HEADERS = {"User-Agent": "OC2-ModInstaller/1.0 (scott.singleton@i3solutions.com)"}

MODS = [
    # Elevator
    "openblocks-elevator",
    # QoL
    "fast-leaf-decay",
    "betterf3",
    "spice-of-life-carrot-edition",
    # Content
    "just-dire-things",
    "immersiveengineering",
    "trash-cans",
    "torchmaster",
    "storage-drawers",
    "framed-blocks",
    "pipez",
    "simplylight",
    "flux-networks",
    # YUNG's Better series
    "yungs-api",
    "yungs-better-caves",
    "yungs-better-mineshafts",
    "yungs-better-ocean-monuments",
    "yungs-better-desert-temples",
    "yungs-better-dungeons",
    "yungs-better-strongholds",
    "yungs-better-nether-fortresses",
    "yungs-better-end-cities",
    "yungs-better-witch-huts",
    "yungs-better-jungle-temples",
    # KubeJS addons
    "kubejs-create",
    "probejs",
    "lychee",
]

downloaded: set[str] = set()
failed: list[str] = []
MODS_DIR.mkdir(parents=True, exist_ok=True)


def fetch(url: str) -> bytes | None:
    req = urllib.request.Request(url, headers=HEADERS)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.read()
    except Exception as e:
        print(f"  fetch error: {e}")
        return None


def download_file(url: str, dest: Path):
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=120) as r, open(dest, "wb") as f:
        total = int(r.headers.get("Content-Length", 0))
        done = 0
        while chunk := r.read(65536):
            f.write(chunk)
            done += len(chunk)
            if total:
                print(f"\r  {done * 100 // total:3d}%", end="", flush=True)
    print()


def install(slug: str, depth: int = 0):
    indent = "  " * depth
    if slug in downloaded:
        return
    downloaded.add(slug)

    print(f"{indent}-> {slug}")
    params = urllib.parse.urlencode({
        "game_versions": json.dumps([GAME_VERSION]),
        "loaders": json.dumps([LOADER]),
    })
    data = fetch(f"{API}/project/{slug}/version?{params}")
    if not data:
        failed.append(slug)
        return
    versions = json.loads(data)
    if not versions:
        print(f"{indent}  NOT FOUND for NeoForge {GAME_VERSION}")
        failed.append(slug)
        return

    best = next((v for v in versions if v["version_type"] == "release"), versions[0])
    primary = next((f for f in best["files"] if f.get("primary")), best["files"][0])
    filename = primary["filename"]
    dest = MODS_DIR / filename

    if dest.exists():
        print(f"{indent}  already present: {filename}")
    else:
        print(f"{indent}  downloading {filename} ...")
        download_file(primary["url"], dest)

    for dep in best.get("dependencies", []):
        if dep.get("dependency_type") != "required":
            continue
        proj_id = dep.get("project_id")
        if not proj_id:
            continue
        proj_data = fetch(f"{API}/project/{proj_id}")
        if proj_data:
            proj = json.loads(proj_data)
            install(proj["slug"], depth + 1)


for slug in MODS:
    install(slug)

print("\n=== DONE ===")
if failed:
    print(f"Failed ({len(failed)}): {', '.join(failed)}")
else:
    print("All installed successfully.")
