import gzip
import json
import os
import re
import subprocess
from functools import cache
from pathlib import Path
from zipfile import ZipFile

from google.protobuf import json_format

import index_pb2

APPLICATION_ICON_320_REGEX = re.compile(
    r"^application-icon-320:'([^']+)'", re.MULTILINE
)
LANGUAGE_REGEX = re.compile(r"tachiyomi-([^.]+)")


@cache
def aapt() -> Path:
    *_, build_tools = (Path(os.environ["ANDROID_HOME"]) / "build-tools").iterdir()
    return build_tools / "aapt"


# Build outputs collected by the workflow: one dir per extension, each holding the source-info
# JSON emitted by assembleRelease plus its outputs/apk|jar/release siblings.
ARTIFACTS_DIR = Path.home() / "apk-artifacts"

# The checked-out `repo` branch we publish into (the working directory).
REPO_DIR = Path.cwd()
REPO_APK_DIR = REPO_DIR / "apk"
REPO_JAR_DIR = REPO_DIR / "jar"
REPO_ICON_DIR = REPO_DIR / "icon"
REPO_APK_DIR.mkdir(parents=True, exist_ok=True)
REPO_JAR_DIR.mkdir(parents=True, exist_ok=True)
REPO_ICON_DIR.mkdir(parents=True, exist_ok=True)

APK_BASE_URL = "https://cdn.jsdelivr.net/gh/AwkwardPeak7/mihon-extensions@repo/apk"
JAR_BASE_URL = "https://raw.githubusercontent.com/AwkwardPeak7/mihon-extensions/repo/jar"
ICON_BASE_URL = "https://cdn.jsdelivr.net/gh/AwkwardPeak7/mihon-extensions@repo/icon"

# Full rebuild: build a fresh index from every artifact, copying each apk/jar into the repo and
# extracting its launcher icon straight out of the apk.
extensions: list[index_pb2.Extension] = []

for info_file in ARTIFACTS_DIR.glob("**/keiyoushi-source-info.json"):
    with info_file.open(encoding="utf-8") as f:
        info = json.load(f)
    package_name = info["packageName"]

    apk = next((info_file.parent / "outputs/apk/release").glob("*.apk"), None)
    if apk is None:
        raise FileNotFoundError(
            f"{package_name}: no release apk found under {info_file.parent}"
        )
    apk_name = apk.name.replace("-release.apk", ".apk")
    (REPO_APK_DIR / apk_name).write_bytes(apk.read_bytes())

    jar = next((info_file.parent / "outputs/jar/release").glob("*.jar"), None)
    if jar is None:
        raise FileNotFoundError(
            f"{package_name}: no release jar found under {info_file.parent}"
        )
    (REPO_JAR_DIR / jar.name).write_bytes(jar.read_bytes())

    badging = subprocess.check_output(
        [aapt(), "dump", "--include-meta-data", "badging", apk]
    ).decode()
    application_icon = APPLICATION_ICON_320_REGEX.search(badging).group(1)
    with (
        ZipFile(apk) as z,
        z.open(application_icon) as i,
        (REPO_ICON_DIR / f"{package_name}.png").open("wb") as f,
    ):
        f.write(i.read())

    extensions.append(
        index_pb2.Extension(
            name=info["name"],
            packageName=package_name,
            resources=index_pb2.Resources(
                apkUrl=f"{APK_BASE_URL}/{apk_name}",
                jarUrl=f"{JAR_BASE_URL}/{jar.name}",
                iconUrl=f"{ICON_BASE_URL}/{package_name}.png",
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
    )

extensions.sort(key=lambda ext: ext.packageName)

index = index_pb2.Index(
    name="AwkwardPeak7's Mihon Extensions",
    badgeLabel="AWP7",
    signingKey="45ab79cda84203f0c2aa6bd54db46932f9d7085da9f2444dc32de58e4696655e",
    contact=index_pb2.Contact(
        website="https://github.com/AwkwardPeak7/mihon-extensions"
    ),
    extensionList=index_pb2.ExtensionList(extensions=extensions),
)

with REPO_DIR.joinpath("index.pb").open("wb") as f:
    f.write(gzip.compress(index.SerializeToString()))


def get_legacy_lang(ext) -> str:
    lang = LANGUAGE_REGEX.search(ext.resources.apkUrl.split("/")[-1]).group(1)
    if len(ext.sources) == 1:
        source_language = ext.sources[0].language
        if (
            source_language != lang
            and source_language not in {"all", "other"}
            and lang not in {"all", "other"}
        ):
            lang = source_language
    return lang


legacy_json_index = [
    {
        "name": f"Tachiyomi: {ext.name}",
        "pkg": ext.packageName,
        "apk": ext.resources.apkUrl.split("/")[-1],
        "lang": get_legacy_lang(ext),
        "code": ext.versionCode,
        "version": ext.versionName,
        "nsfw": 1 if ext.contentWarning > 2 else 0,
        "sources": [
            {
                "name": source.name,
                "lang": source.language,
                "id": str(source.id),
                "baseUrl": source.homeUrl,
            }
            for source in ext.sources
        ],
    }
    for ext in extensions
]

with REPO_DIR.joinpath("index.min.json").open("w", encoding="utf-8") as f:
    json.dump(legacy_json_index, f, ensure_ascii=False, separators=(",", ":"))
