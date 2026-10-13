#!/usr/bin/env python3
"""查询 gradle/libs.versions.toml 中各依赖的最新正式版（release）。

项目规定依赖一律使用最新正式版，禁止 alpha / beta / rc / dev / SNAPSHOT / M 等预发布版本，
且不能凭记忆填写版本号（见 CLAUDE.md §4）。本脚本到 Google Maven 和 Maven Central 查询
maven-metadata.xml，列出每个依赖的当前版本与最新正式版，并标出需要升级的项。

用法：
    python3 -I scripts/dev/latest_versions.py                 # 检查 libs.versions.toml
    python3 -I scripts/dev/latest_versions.py group:artifact  # 查询任意坐标的最新正式版
"""

import re
import sys
import tomllib
import urllib.request
from pathlib import Path

# 依赖仓库：Google Maven 优先（androidx、com.android 等），其次 Maven Central 及其 Google 镜像
# （云环境直连 Maven Central 偶尔会被限流返回 429，所以镜像作为备用），最后是 Gradle 插件门户
REPOS = [
    "https://dl.google.com/dl/android/maven2",
    "https://repo1.maven.org/maven2",
    "https://maven-central.storage-download.googleapis.com/maven2",
    "https://plugins.gradle.org/m2",
]

# 预发布版本的特征：alpha、beta、rc、dev、SNAPSHOT、M1 这类里程碑、eap、preview 等
PRE_RELEASE = re.compile(r"(alpha|beta|rc|dev|snapshot|eap|preview|-m\d|\.m\d|milestone|cr\d)", re.IGNORECASE)

# 插件 ID 与实际 Maven 坐标不一致时的映射（其余插件按 Gradle 插件标记坐标 id:id.gradle.plugin 查询）
PLUGIN_COORDS = {
    "com.android.application": "com.android.tools.build:gradle",
    "com.android.library": "com.android.tools.build:gradle",
}


def fetch_versions(coord: str) -> list[str]:
    """从各仓库读取某个坐标的全部版本号。

    :param coord: Maven 坐标，格式 group:artifact
    :return: 版本号列表；所有仓库都查不到时返回空列表
    """
    group, artifact = coord.split(":")
    path = f"{group.replace('.', '/')}/{artifact}/maven-metadata.xml"
    for repo in REPOS:
        try:
            with urllib.request.urlopen(f"{repo}/{path}", timeout=20) as resp:
                xml = resp.read().decode("utf-8")
            versions = re.findall(r"<version>([^<]+)</version>", xml)
            if versions:
                return versions
        except Exception:  # noqa: BLE001 —— 某个仓库没有该坐标或网络失败时换下一个仓库
            continue
    return []


def version_key(v: str):
    """把版本号转为可比较的元组，数字段按数值比较。

    :param v: 版本号字符串
    :return: 用于排序的元组
    """
    return tuple(int(p) if p.isdigit() else -1 for p in re.split(r"[.\-]", v))


def latest_release(coord: str) -> str | None:
    """查询某个坐标的最新正式版。

    :param coord: Maven 坐标，格式 group:artifact
    :return: 最新正式版号；查不到时返回 None
    """
    releases = [v for v in fetch_versions(coord) if not PRE_RELEASE.search(v)]
    return max(releases, key=version_key) if releases else None


def check_catalog(path: Path) -> None:
    """逐项检查版本目录中的依赖与插件，打印当前版本和最新正式版。

    :param path: libs.versions.toml 的路径
    """
    data = tomllib.loads(path.read_text(encoding="utf-8"))
    versions = data.get("versions", {})
    rows = []
    for name, lib in data.get("libraries", {}).items():
        coord = lib.get("module") or f"{lib['group']}:{lib['name']}"
        current = versions.get(lib["version"]["ref"]) if isinstance(lib.get("version"), dict) and "ref" in lib["version"] else lib.get("version")
        rows.append((name, coord, current))
    for name, plugin in data.get("plugins", {}).items():
        pid = plugin["id"]
        coord = PLUGIN_COORDS.get(pid, f"{pid}:{pid}.gradle.plugin")
        ref = plugin.get("version", {})
        current = versions.get(ref["ref"]) if isinstance(ref, dict) else ref
        rows.append((f"plugin:{name}", coord, current))

    for name, coord, current in rows:
        latest = latest_release(coord)
        flag = "" if latest == current else "  <== 可升级" if latest else "  <== 查询失败"
        print(f"{name:40s} {coord:60s} 当前 {current or '-':12s} 最新 {latest or '-'}{flag}")


def main() -> None:
    """命令行入口：无参数时检查版本目录，有参数时查询指定坐标。"""
    if len(sys.argv) > 1:
        for coord in sys.argv[1:]:
            print(f"{coord}: {latest_release(coord)}")
        return
    root = Path(__file__).resolve().parents[2]
    check_catalog(root / "gradle" / "libs.versions.toml")


if __name__ == "__main__":
    main()
