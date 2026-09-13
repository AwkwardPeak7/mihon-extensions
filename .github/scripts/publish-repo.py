import gzip
import hashlib
import html
import json
import math
import os
import shutil
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

import index_pb2
from github_utils import REPO_NAME, run_gh
from google.protobuf import json_format

# Artifacts downloaded from the build jobs: one APK per extension plus the source metadata JSON
# emitted by each assembleRelease.
ARTIFACTS_DIR = Path.home() / "apk-artifacts"

# The checked-out `repo` branch we publish into (the working directory).
REPO_DIR = Path.cwd()

# Files from the pre-release publishing scheme; replaced by index.json + releases.
LEGACY_PATHS = ["index.min.json", "apk", "jar", "icon"]

# Icons live on the `repo` branch under `icons/`, mirroring the source-tree paths from which
# they were copied; served through jsDelivr since the source repo is private.
ICON_BASE_URL = "https://cdn.jsdelivr.net/gh/AwkwardPeak7/mihon-extensions@repo/icons"
RELEASE_BASE_URL = f"https://github.com/{REPO_NAME}/releases/download"
ASSET_LIMIT = 495  # Actual limit is 1000 but we upload 2 items per extension.
UPLOAD_CHUNK_SIZE = 80
UPLOAD_CHUNK_INTERVAL = 30
DELETE_INTERVAL = 1
BOT_NAME = "github-actions[bot]"
BOT_EMAIL = "github-actions[bot]@users.noreply.github.com"
PURGE_URLS = [
    "https://purge.jsdelivr.net/gh/AwkwardPeak7/mihon-extensions@repo/index.json",
    "https://purge.jsdelivr.net/gh/AwkwardPeak7/mihon-extensions@repo/index.pb",
]

# Everything is rebuilt on every run, so the fresh build output is the source of truth: any
# extension absent from it was deleted and simply drops out of the index and release assets.
current_sha = sys.argv[1]
current_sha_short = current_sha[:7]

index_path = REPO_DIR / "index.json"
if index_path.exists():
    with index_path.open() as f:
        remote_proto = json_format.Parse(f.read(), index_pb2.Index())
else:
    # First run after migrating from the legacy scheme: start from an empty index; every
    # built extension is new and gets a release of its own.
    remote_proto = index_pb2.Index()

remote_extensions = {
    ext.packageName: ext for ext in remote_proto.extensionList.extensions
}

release_assets_path = REPO_DIR / "release-assets.json"
if release_assets_path.exists():
    with release_assets_path.open() as f:
        release_assets = json.load(f)
else:
    release_assets = {}

updated_release_assets: dict[str, dict] = {}

# Build index entries for the freshly built apks. Each extension's metadata comes from the
# source-info JSON emitted by its assembleRelease task (see GenerateSourceInfoTask); its APK is a
# sibling in the same build dir. aapt reads the icon out of the APK
new_extensions: list[tuple[index_pb2.Extension, Path, Path, bool, bool]] = []

# The source code checkout containing each extension's icon files; resolved relative to the
# script's location by default, overridable via the $SOURCE_DIR environment variable (used in CI
# where the source lives in a sibling checkout of the private source repo).
SOURCE_DIR = Path(
    os.environ.get("SOURCE_DIR", Path(__file__).resolve().parents[2])
).resolve()
ICON_FILE = "res/mipmap-xhdpi/ic_launcher.png"
ICON_DIR = REPO_DIR / "icons"


def icon_candidates(module: str, theme: str | None) -> list[str]:
    candidates = [f"src/{module.replace('.', '/')}/{ICON_FILE}"]
    if theme:
        candidates.append(f"lib-multisrc/{theme}/{ICON_FILE}")
    candidates.append(f"core/src/main/{ICON_FILE}")
    return candidates


def get_icon_source(module: str, theme: str | None) -> Path:
    for candidate in icon_candidates(module, theme):
        candidate = SOURCE_DIR / candidate
        if candidate.exists():
            return candidate
    raise FileNotFoundError(f"no icon found for {module}")


referenced_icons: set[Path] = set()


def publish_icon(module: str, theme: str | None) -> str:
    icon_source = get_icon_source(module, theme)
    icon_dest = ICON_DIR / icon_source.relative_to(SOURCE_DIR)
    icon_dest.parent.mkdir(parents=True, exist_ok=True)
    if icon_source != icon_dest.resolve():
        shutil.copyfile(icon_source, icon_dest)
    referenced_icons.add(icon_dest)
    return f"{ICON_BASE_URL}/{icon_dest.relative_to(ICON_DIR)}"


def prune_icons() -> None:
    # The fresh build covers every extension, so any icon not referenced this run belongs to a
    # removed extension, source path or stale legacy copy.
    stale = [p for p in ICON_DIR.glob("**/*.png") if p not in referenced_icons]
    for icon in stale:
        icon.unlink()
        parent = icon.parent
        while parent != ICON_DIR:
            try:
                parent.rmdir()
            except OSError:
                break
            parent = parent.parent


for info_file in ARTIFACTS_DIR.glob("**/keiyoushi-source-info.json"):
    with info_file.open(encoding="utf-8") as f:
        info = json.load(f)
    package_name = info["packageName"]
    apk = next((info_file.parent / "outputs/apk/release").glob("*.apk"), None)
    if apk is None:
        raise FileNotFoundError(
            f"{package_name}: no release apk found under {info_file.parent}"
        )

    jar = next((info_file.parent / "outputs/jar/release").glob("*.jar"), None)
    if jar is None:
        raise FileNotFoundError(
            f"{package_name}: no release jar found under {info_file.parent}"
        )

    assets = {
        "apk": {
            "name": apk.name,
            "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        },
        "jar": {
            "name": jar.name,
            "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
        },
    }
    old_assets = release_assets.get(package_name, {})
    apk_changed = (
        package_name not in remote_extensions
        or old_assets.get("apk") != assets["apk"]
    )
    jar_changed = (
        package_name not in remote_extensions
        or old_assets.get("jar") != assets["jar"]
    )

    updated_release_assets[package_name] = assets

    ext = index_pb2.Extension(
        name=info["name"],
        packageName=package_name,
        resources=index_pb2.Resources(
            iconUrl=publish_icon(info["module"], info.get("theme")),
        ),
        extensionLib=info["extensionLib"],
        versionCode=info["versionCode"],
        versionName=info["versionName"],
        contentWarning=info["contentWarning"],
        sources=[
            index_pb2.Source(
                id=int(source["id"]),
                name=source["name"],
                language=source["lang"],
                homeUrl=source["baseUrl"],
                mirrorUrls=source.get("mirrorUrls", []),
            )
            for source in info["sources"]
        ],
    )
    new_extensions.append((ext, apk, jar, apk_changed, jar_changed))

new_extensions.sort(key=lambda item: item[0].packageName)
prune_icons()

changed_extensions = [item for item in new_extensions if item[3] or item[4]]
total_changed_extensions = len(changed_extensions)
release_count = (
    math.ceil(total_changed_extensions / ASSET_LIMIT)
    if total_changed_extensions
    else 0
)
ext_per_release = (
    math.ceil(total_changed_extensions / release_count) if release_count else 1
)


def get_release_tag(batch_index: int) -> str:
    return (
        f"{current_sha_short}-{batch_index}" if release_count > 1 else current_sha_short
    )


changed_index = 0
for ext, apk, jar, apk_changed, jar_changed in new_extensions:
    if apk_changed or jar_changed:
        tag = get_release_tag(changed_index // ext_per_release)
        old_resources = remote_extensions.get(ext.packageName)
        ext.resources.apkUrl = (
            f"{RELEASE_BASE_URL}/{tag}/{apk.name}"
            if apk_changed
            else old_resources.resources.apkUrl
        )
        ext.resources.jarUrl = (
            f"{RELEASE_BASE_URL}/{tag}/{jar.name}"
            if jar_changed
            else old_resources.resources.jarUrl
        )
        changed_index += 1
    else:
        old_resources = remote_extensions[ext.packageName].resources
        ext.resources.apkUrl = old_resources.apkUrl
        ext.resources.jarUrl = old_resources.jarUrl

# The fresh build covers every extension, so the index is rebuilt from scratch; unchanged
# extensions keep their existing release URLs from the merge above.
final_extensions = [ext for ext, _, _, _, _ in new_extensions]
final_extensions.sort(key=lambda ext: ext.packageName)

index = index_pb2.Index(
    name="AwkwardPeak7's Mihon Extensions",
    badgeLabel="AWP7",
    signingKey="45ab79cda84203f0c2aa6bd54db46932f9d7085da9f2444dc32de58e4696655e",
    contact=index_pb2.Contact(
        website="https://github.com/AwkwardPeak7/mihon-extensions"
    ),
    extensionList=index_pb2.ExtensionList(extensions=final_extensions),
)

with index_path.open("w", encoding="utf-8") as f:
    f.write(
        json_format.MessageToJson(
            index,
            always_print_fields_with_no_presence=False,
            preserving_proto_field_name=True,
        )
    )

with REPO_DIR.joinpath("index.pb").open("wb") as f:
    f.write(gzip.compress(index.SerializeToString(deterministic=True), mtime=0))

with release_assets_path.open("w", encoding="utf-8") as f:
    json.dump(updated_release_assets, f, indent=2, sort_keys=True)
    f.write("\n")

with REPO_DIR.joinpath("index.html").open("w", encoding="utf-8") as f:
    f.write(
        '<!DOCTYPE html>\n<html>\n<head>\n<meta charset="UTF-8">\n<title>apks</title>\n</head>\n<body>\n<pre>\n'
    )
    for ext in final_extensions:
        apk_escaped = html.escape(ext.resources.apkUrl)
        name_escaped = html.escape(f"Tachiyomi: {ext.name}")
        f.write(f'<a href="{apk_escaped}">{name_escaped}</a>\n')
    f.write("</pre>\n</body>\n</html>\n")

# --- Upload assets as releases ---
def create_release(tag: str):
    if run_gh(
        "release",
        "view",
        tag,
        "--repo",
        REPO_NAME,
        "--json",
        "tagName",
        success_errors=("release not found",),
    ):
        print(f"Release {tag} already exists")
        return

    print(f"Creating release {tag}")
    run_gh(
        "release",
        "create",
        tag,
        "--repo",
        REPO_NAME,
        "--draft",
        "--title",
        f"Repository Update {tag}",
        "--notes",
        f"Automated update from AwkwardPeak7/mihon-extensions@{current_sha}",
    )


def publish_release(tag: str):
    print(f"Publishing release {tag}")
    run_gh("release", "edit", tag, "--repo", REPO_NAME, "--draft=false")


def get_release_assets(tag: str) -> dict[str, str]:
    release = json.loads(
        run_gh(
            "release",
            "view",
            tag,
            "--repo",
            REPO_NAME,
            "--json",
            "assets",
        )
    )
    return {
        asset["name"]: (asset.get("digest") or "").removeprefix("sha256:")
        for asset in release["assets"]
    }


def upload_assets(tag: str, files: list[Path]):
    if not files:
        return

    existing_assets = get_release_assets(tag)
    files_to_upload = [
        file
        for file in files
        if existing_assets.get(file.name)
        != hashlib.sha256(file.read_bytes()).hexdigest()
    ]
    skipped = len(files) - len(files_to_upload)
    print(f"Uploading {len(files_to_upload)} assets to {tag}, skipping {skipped}")

    for i in range(0, len(files_to_upload), UPLOAD_CHUNK_SIZE):
        chunk = files_to_upload[i : i + UPLOAD_CHUNK_SIZE]
        if i:
            time.sleep(UPLOAD_CHUNK_INTERVAL)
        print(f"  assets {i + 1}-{i + len(chunk)} of {len(files_to_upload)}")
        run_gh(
            "release",
            "upload",
            tag,
            *[str(f) for f in chunk],
            "--repo",
            REPO_NAME,
            "--clobber",
        )
    publish_release(tag)


for i in range(0, total_changed_extensions, ext_per_release):
    batch = changed_extensions[i : i + ext_per_release]
    tag = get_release_tag(i // ext_per_release)
    files_to_upload = [
        file
        for _, apk, jar, apk_changed, jar_changed in batch
        for file, changed in ((apk, apk_changed), (jar, jar_changed))
        if changed
    ]

    create_release(tag)
    upload_assets(tag, files_to_upload)


# --- Commit and push the repo branch ---
# Must happen before cleanup: the freshly written index is what makes the new release URLs
# live, and cleanup must only delete assets once nothing published references them anymore.
def run_git(*args: str) -> str:
    result = subprocess.run(
        ["git", *args],
        capture_output=True,
        encoding="utf-8",
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def push_repo() -> None:
    for legacy in LEGACY_PATHS:
        path = REPO_DIR / legacy
        if path.is_dir():
            shutil.rmtree(path)
        elif path.exists():
            path.unlink()

    run_git("add", "-A")
    if not run_git("status", "--porcelain"):
        print("No changes to commit")
        return

    run_git(
        "-c", f"user.name={BOT_NAME}", "-c", f"user.email={BOT_EMAIL}",
        "commit", "-m", "Update extensions repo",
    )
    run_git("push")

    for url in PURGE_URLS:
        try:
            urllib.request.urlopen(url, timeout=30).read()
        except OSError as e:
            print(f"jsDelivr purge failed for {url}: {e}")


push_repo()


# --- Clean up unreferenced release assets ---
# The freshly built index is the source of truth: any release asset whose download URL is not
# referenced by it is leftover from a deleted or superseded extension.
referenced_assets = {
    url
    for ext in final_extensions
    for url in (ext.resources.apkUrl, ext.resources.jarUrl)
}


def get_pages(endpoint: str) -> list[dict]:
    items = []
    page = 1
    separator = "&" if "?" in endpoint else "?"
    while True:
        batch = json.loads(
            run_gh("api", f"{endpoint}{separator}per_page=100&page={page}")
        )
        items.extend(batch)
        if len(batch) < 100:
            return items
        page += 1


def delete_release(release: dict):
    run_gh("api", "--method", "DELETE", f"repos/{REPO_NAME}/releases/{release['id']}")
    # Deleting a release does not remove its git tag; clean that up too.
    run_gh(
        "api",
        "--method",
        "DELETE",
        f"repos/{REPO_NAME}/git/refs/tags/{release['tag_name']}",
        success_errors=("does not exist",),
    )


def cleanup_releases() -> None:
    deleted_assets = 0
    deleted_releases = 0
    for release in get_pages(f"repos/{REPO_NAME}/releases"):
        assets = get_pages(f"repos/{REPO_NAME}/releases/{release['id']}/assets")

        # Fast path: every asset is unreferenced -> delete the release in one call,
        # which takes the assets with it.
        if assets and not any(
            asset["browser_download_url"] in referenced_assets for asset in assets
        ):
            print(
                f"Deleting release {release['tag_name']} "
                f"({len(assets)} unreferenced assets)"
            )
            delete_release(release)
            deleted_assets += len(assets)
            deleted_releases += 1
            time.sleep(DELETE_INTERVAL)
            continue

        remaining = len(assets)
        for asset in assets:
            if asset["browser_download_url"] in referenced_assets:
                continue

            print(f"Deleting {release['tag_name']}/{asset['name']}")
            run_gh(
                "api",
                "--method",
                "DELETE",
                f"repos/{REPO_NAME}/releases/assets/{asset['id']}",
            )
            deleted_assets += 1
            remaining -= 1
            time.sleep(DELETE_INTERVAL)

        if remaining == 0:
            print(f"Deleting empty release {release['tag_name']}")
            delete_release(release)
            deleted_releases += 1

    if deleted_assets or deleted_releases:
        print(
            f"Deleted {deleted_assets} unreferenced assets "
            f"and {deleted_releases} empty releases"
        )


cleanup_releases()
