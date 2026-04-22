"""Download mods + required deps from Modrinth for NeoForge 1.21.1."""

import json, os, sys, urllib.request, urllib.parse
from pathlib import Path

MODS_DIR = Path(r"C:\Users\ScottSingleton\AppData\Roaming\PrismLauncher\instances\OC2 Test platform\minecraft\mods")
GAME_VERSION = "1.21.1"
LOADER = "neoforge"
API = "https://api.modrinth.com/v2"
HEADERS = {"User-Agent": "OC2-ModInstaller/1.0 (scott.singleton@i3solutions.com)"}

MODS = [
    "jei",
    "mouse-tweaks",
    "appleskin",
    "jade",
    "waystones",
    "sophisticated-backpacks",
    "sophisticated-storage",
    "sophisticated-core",
    # FTB suite
    "ftb-chunks-neoforge",
    "ftb-teams-neoforge",
    "ftb-quests-neoforge",
    "ftb-library-neoforge",
    "ftb-essentials-neoforge",
    "ftb-ultimine-neoforge",
    # Storage / logistics
    "ae2",
    "refined-storage",
]

downloaded: set[str] = set()
failed: list[str] = []

def api_get(path: str) -> dict | list | None:
    url = f"{API}{path}"
    req = urllib.request.Request(url, headers=HEADERS)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return json.loads(r.read())
    except Exception as e:
        print(f"  API error {url}: {e}")
        return None

def get_best_version(slug: str) -> dict | None:
    params = urllib.parse.urlencode({
        "game_versions": json.dumps([GAME_VERSION]),
        "loaders": json.dumps([LOADER]),
    })
    data = api_get(f"/project/{slug}/version?{params}")
    if not data:
        return None
    versions = [v for v in data if v.get("version_type") in ("release", "beta")]
    if not versions:
        versions = data  # fallback: take anything
    return versions[0] if versions else None

def download_file(url: str, dest: Path):
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=60) as r, open(dest, "wb") as f:
        total = int(r.headers.get("Content-Length", 0))
        done = 0
        while chunk := r.read(65536):
            f.write(chunk)
            done += len(chunk)
            if total:
                pct = done * 100 // total
                print(f"\r  {pct:3d}%", end="", flush=True)
    print()

def install(slug: str, depth: int = 0):
    indent = "  " * depth
    if slug in downloaded:
        return
    downloaded.add(slug)

    print(f"{indent}-> {slug}")
    version = get_best_version(slug)
    if not version:
        # try fabric loader fallback slug variants
        print(f"{indent}  NOT FOUND on Modrinth for NeoForge 1.21.1")
        failed.append(slug)
        return

    primary = next((f for f in version["files"] if f.get("primary")), version["files"][0])
    filename = primary["filename"]
    dest = MODS_DIR / filename

    if dest.exists():
        print(f"{indent}  already present: {filename}")
    else:
        print(f"{indent}  downloading {filename} ...")
        download_file(primary["url"], dest)

    for dep in version.get("dependencies", []):
        if dep.get("dependency_type") != "required":
            continue
        dep_project_id = dep.get("project_id")
        if not dep_project_id:
            continue
        # resolve slug from project id
        proj = api_get(f"/project/{dep_project_id}")
        if proj:
            install(proj["slug"], depth + 1)

MODS_DIR.mkdir(parents=True, exist_ok=True)

for slug in MODS:
    install(slug)

print("\n=== DONE ===")
if failed:
    print(f"Failed ({len(failed)}): {', '.join(failed)}")
else:
    print("All mods installed successfully.")
