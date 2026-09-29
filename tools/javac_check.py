#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
不跑 Gradle 的情况下做一次 Java 语法 / 类型检查。

做法：
1. 从 Gradle 依赖缓存里收集所有 jar（AAR 里的 classes.jar 会自动解压出来）；
2. 扫描 res/ 生成一个 R.java 桩（AGP 正常构建时才会生成）；
3. 用 javac 编译 app/src/main/java 下的全部源文件。

只做检查，不产出 APK。用法：
    python tools/javac_check.py
可选参数：
    --gradle-home  依赖缓存根目录，默认 C:/myAI/gradle-home
    --sdk          Android SDK 目录，默认读 local.properties
"""
import argparse
import io
import os
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# 临时文件放在系统临时目录，避免污染工程目录
TMP = os.path.join(tempfile.gettempdir(), "doudou_javac_check")


def collect_classpath(gradle_home, android_jar):
    jars = [android_jar]
    cache = os.path.join(gradle_home, "caches", "modules-2", "files-2.1")
    aar_out = os.path.join(TMP, "aars")
    os.makedirs(aar_out, exist_ok=True)
    if os.path.isdir(cache):
        for dirpath, _dirs, files in os.walk(cache):
            for name in files:
                path = os.path.join(dirpath, name)
                if name.endswith(".jar") and not name.endswith("-sources.jar") \
                        and "javadoc" not in name:
                    jars.append(path)
                elif name.endswith(".aar"):
                    try:
                        with zipfile.ZipFile(path) as z:
                            if "classes.jar" in z.namelist():
                                target = os.path.join(aar_out, "aar_%d.jar" % len(jars))
                                with open(target, "wb") as out:
                                    out.write(z.read("classes.jar"))
                                jars.append(target)
                    except Exception:
                        pass
    return jars


def generate_r_stub():
    res = os.path.join(ROOT, "app", "src", "main", "res")
    names = {}

    def add(kind, name):
        names.setdefault(kind, set()).add(name)

    values_dir = os.path.join(res, "values")
    if os.path.isdir(values_dir):
        for f in os.listdir(values_dir):
            if not f.endswith(".xml"):
                continue
            for child in ET.parse(os.path.join(values_dir, f)).getroot():
                if not isinstance(child.tag, str):
                    continue
                name = child.attrib.get("name")
                if not name:
                    continue
                if child.tag in ("string", "color", "dimen", "style", "bool", "integer"):
                    add(child.tag, name.replace(".", "_"))
                elif child.tag == "item" and child.attrib.get("type") == "id":
                    add("id", name)

    for dirpath, _dirs, files in os.walk(res):
        base = os.path.basename(dirpath)
        kind = None
        if base.startswith("layout"):
            kind = "layout"
        elif base.startswith("mipmap"):
            kind = "mipmap"
        elif base.startswith("drawable"):
            kind = "drawable"
        elif base.startswith("menu"):
            kind = "menu"
        elif base.startswith("xml"):
            kind = "xml"
        for name in files:
            if kind and "." in name:
                add(kind, name.split(".")[0])
            if name.endswith(".xml"):
                text = io.open(os.path.join(dirpath, name), encoding="utf-8").read()
                for m in re.finditer(r'@\+id/([A-Za-z_][A-Za-z0-9_]*)', text):
                    add("id", m.group(1))

    lines = ["package com.doudou.x;", "", "public final class R {"]
    for kind in sorted(names):
        lines.append("    public static final class %s {" % kind)
        for value in sorted(names[kind]):
            lines.append("        public static final int %s = 0;" % value)
        lines.append("    }")
    lines.append("}")
    gen_dir = os.path.join(TMP, "gen", "com", "doudou", "x")
    os.makedirs(gen_dir, exist_ok=True)
    r_file = os.path.join(gen_dir, "R.java")
    with io.open(r_file, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    return r_file


def find_sources():
    src_root = os.path.join(ROOT, "app", "src", "main", "java")
    sources = [os.path.join(TMP, "gen", "com", "doudou", "x", "R.java")]
    for dirpath, _dirs, files in os.walk(src_root):
        for name in files:
            if name.endswith(".java"):
                sources.append(os.path.join(dirpath, name))
    return sources


def read_sdk_dir():
    props = os.path.join(ROOT, "local.properties")
    if os.path.exists(props):
        for line in io.open(props, encoding="utf-8"):
            if line.strip().startswith("sdk.dir="):
                return line.strip().split("=", 1)[1].replace("\\\\", "\\")
    return os.path.expanduser("~/AppData/Local/Android/Sdk")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--gradle-home", default="C:/myAI/gradle-home")
    parser.add_argument("--sdk", default=None)
    parser.add_argument("--platform", default="android-35")
    args = parser.parse_args()

    sdk = args.sdk or read_sdk_dir()
    android_jar = os.path.join(sdk, "platforms", args.platform, "android.jar")
    if not os.path.exists(android_jar):
        print("找不到 android.jar：%s" % android_jar)
        return 2

    os.makedirs(TMP, exist_ok=True)
    classpath = collect_classpath(args.gradle_home, android_jar)
    generate_r_stub()
    sources = find_sources()
    out_dir = os.path.join(TMP, "out")
    os.makedirs(out_dir, exist_ok=True)

    cmd = ["javac", "-nowarn", "-proc:none", "-d", out_dir,
           "-cp", os.pathsep.join(classpath)] + sources
    print("编译 %d 个源文件（仅检查，不产出 APK）…" % len(sources))
    result = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                            errors="replace")
    if result.returncode == 0:
        print("✅ 检查通过：无编译错误")
    else:
        print(result.stdout or "")
        print(result.stderr or "")
        print("❌ 存在编译错误")
    return result.returncode


if __name__ == "__main__":
    sys.exit(main())
