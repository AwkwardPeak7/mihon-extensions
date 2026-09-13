#!/usr/bin/env python3
import re
import requests
import tempfile
from zipfile import ZipFile
from pathlib import Path

MODULE_DIR = Path('src/all/mangaplus')
SOURCE_FILE = MODULE_DIR / 'src/io/github/awkwardpeak/extension/all/mangaplus/MangaPlus.kt'
BUILD_FILE = MODULE_DIR / 'build.gradle.kts'


def get_latest_app_ver() -> int | None:
    url = 'https://d.apkpure.com/b/XAPK/jp.co.shueisha.mangaplus?version=latest'
    headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36 Edg/134.0.0.0',
            'Referer': 'https://apkpure.com/manga-plus-by-shueisha/jp.co.shueisha.mangaplus/download'
            }

    with tempfile.TemporaryDirectory() as tmp_dir:
        xapk_path = tempfile.NamedTemporaryFile(dir=tmp_dir, suffix='.xapk', delete=False).name
        with requests.get(url, headers=headers, stream=True) as response:
            response.raise_for_status()
            total = int(response.headers.get('content-length', 0))
            downloaded = 0
            with open(xapk_path, 'wb') as f:
                for chunk in response.iter_content(chunk_size=8192):
                    if chunk:
                        f.write(chunk)
                        downloaded += len(chunk)
                        if total:
                            percent = downloaded * 100 // total
                            print(f"\rDownloading XAPK: {percent}%", end='', flush=True)
            if total:
                print()  # Newline after progress

        with ZipFile(xapk_path, 'r') as f:
            names = f.namelist()
            # base apk may be named after the package or the xapk itself
            candidates = [n for n in names if n.endswith('.apk') and ('mangaplus' in n.lower() or 'base' in n.lower() or n.count('/') == 0)]
            apk_name = candidates[0] if candidates else next(n for n in names if n.endswith('.apk'))
            print(f"Extracting {apk_name}")
            f.extract(apk_name, tmp_dir)

        apk_path = Path(tmp_dir, apk_name)

        with open(apk_path, 'rb') as apk_file:
            data = apk_file.read()
            matches = re.findall(rb'app_ver=(\d+)', data)
            if matches:
                return int(matches[-1].decode())
            else:
                return None

def get_current_app_ver() -> int | None:
    if SOURCE_FILE.exists():
        content = SOURCE_FILE.read_text()
        match = re.search(r'private const val APP_VER = "(\d+)"', content)
        if match:
            return int(match.group(1))
    return None

def update_app_ver(new_ver: int) -> None:
    # update app_ver
    content = SOURCE_FILE.read_text()
    new_content = re.sub(r'private const val APP_VER = "\d+"', f'private const val APP_VER = "{new_ver}"', content)
    SOURCE_FILE.write_text(new_content)

    # bump extVersionCode
    content = BUILD_FILE.read_text()
    current_version_code = int(re.search(r'versionCode = (\d+)', content).group(1))
    new_version_code = current_version_code + 1
    new_content = re.sub(r'versionCode = \d+', f'versionCode = {new_version_code}', content)
    BUILD_FILE.write_text(new_content)

if __name__ == "__main__":
    latest_app_ver = get_latest_app_ver()
    current_app_ver = get_current_app_ver()

    if latest_app_ver is None:
        raise Exception("Failed to retrieve the latest app version.")
    else:
        print(f"Latest app version: {latest_app_ver}")
    if current_app_ver is None:
        raise Exception("Failed to retrieve the current app version.")
    else:
        print(f"Current app version: {current_app_ver}")
    if latest_app_ver > current_app_ver:
        print("Updating app version...")
        update_app_ver(latest_app_ver)
        print("App version updated successfully.")
    else:
        print("No update needed. The current app version is up to date.")
