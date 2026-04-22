"""Download FTB + second batch of mods for NeoForge 1.21.1."""

import json, re, sys, urllib.request, urllib.parse
from pathlib import Path

MODS_DIR = Path(r"C:\Users\ScottSingleton\AppData\Roaming\PrismLauncher\instances\OC2 Test platform\minecraft\mods")
GAME_VERSION = "1.21.1"
LOADER = "neoforge"
MODRINTH_API = "https://api.modrinth.com/v2"
FTB_MAVEN = "https://maven.ftb.dev/releases/dev/ftb/mods"
HEADERS = {"User-Agent": "OC2-ModInstaller/1.0 (scott.singleton@i3solutions.com)"}

# Modrinth slugs for second batch
MODRINTH_MODS = [
    "natures-compass",
    "iris",              # Iris Shaders
    "oculus",            # Oculus (NeoForge Iris port)
    "spark",
    "create",
    "carry-on",
    "kubejs",
    "rhino",             # KubeJS dep
    "architectury-api",  # KubeJS dep
    "mekanism",
    "mekanism-generators",
    "mekanism-additions",
    "mekanism-tools",
    "rftools-builder",
    "explorers-compass",
]

# FTB maven mods: (artifact, mc-version-prefix)
# 2101.x = MC 1.21.1
FTB_MC_PREFIX = "2101"
FTB_MODS = [
    "ftb-library-neoforge",
    "ftb-chunks-neoforge",
    "ftb-teams-neoforge",
    "ftb-quests-neoforge",
    "ftb-essentials-neoforge",
    "ftb-ultimine-neoforge",
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
        print(f"  fetch error {url}: {e}")
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


def modrinth_install(slug: str, depth: int = 0):
    indent = "  " * depth
    key = f"modrinth:{slug}"
    if key in downloaded:
        return
    downloaded.add(key)

    print(f"{indent}[modrinth] {slug}")
    params = urllib.parse.urlencode({
        "game_versions": json.dumps([GAME_VERSION]),
        "loaders": json.dumps([LOADER]),
    })
    data = fetch(f"{MODRINTH_API}/project/{slug}/version?{params}")
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
        proj_data = fetch(f"{MODRINTH_API}/project/{proj_id}")
        if proj_data:
            proj = json.loads(proj_data)
            modrinth_install(proj["slug"], depth + 1)


def ftb_install(artifact: str):
    key = f"ftb:{artifact}"
    if key in downloaded:
        return
    downloaded.add(key)

    print(f"[ftb-maven] {artifact}")
    listing = fetch(f"{FTB_MAVEN}/{artifact}/")
    if not listing:
        failed.append(artifact)
        return

    html = listing.decode()
    # find all version dirs matching our MC prefix
    versions = re.findall(rf'href="\./{re.escape(FTB_MC_PREFIX)}\.[^/]+/"', html)
    if not versions:
        print(f"  no versions found for prefix {FTB_MC_PREFIX}")
        failed.append(artifact)
        return

    # sort and take latest
    ver_strings = [re.search(r'"\./(.*?)/"', v).group(1) for v in versions]
    def ver_key(v):
        return tuple(int(x) for x in v.split("."))
    ver_strings.sort(key=ver_key)
    latest = ver_strings[-1]
    print(f"  latest {FTB_MC_PREFIX}.x: {latest}")

    filename = f"{artifact}-{latest}.jar"
    url = f"{FTB_MAVEN}/{artifact}/{latest}/{filename}"
    dest = MODS_DIR / filename
    if dest.exists():
        print(f"  already present: {filename}")
    else:
        print(f"  downloading {filename} ...")
        download_file(url, dest)


print("=== FTB mods (from ftb maven) ===")
for mod in FTB_MODS:
    ftb_install(mod)

print("\n=== Modrinth mods (second batch) ===")
for mod in MODRINTH_MODS:
    modrinth_install(mod)

print("\n=== DONE ===")
if failed:
    print(f"Failed ({len(failed)}): {', '.join(failed)}")
else:
    print("All installed successfully.")
