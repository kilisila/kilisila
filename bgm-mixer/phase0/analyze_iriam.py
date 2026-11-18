#!/usr/bin/env python3
"""IRIAM の音声取得経路と改ざん対策を調べる Phase 0 用の解析スクリプト。

使い方:
    python analyze_iriam.py static    APK を端末から取り出し、静的に解析する
    python analyze_iriam.py runtime   マイク使用中の音声経路を端末から取得する

前提:
    - adb にパスが通っていること
    - 端末で USB デバッグが有効になっていること
    - Python 3.8 以降（標準ライブラリのみ使用）

取り出した APK と生の出力は out/ に保存する。out/ はリポジトリに含めない。
共有してよいのは report_*.txt のみ。生の出力には他のアプリの情報が含まれる。
"""

import argparse
import re
import subprocess
import sys
import zipfile
from pathlib import Path

OUT_DIR = Path(__file__).resolve().parent / "out"

# 実装基盤の判定に使うネイティブライブラリ名（部分一致）
ENGINE_LIBS = {
    "libunity.so": "Unity",
    "libil2cpp.so": "Unity（IL2CPP）",
    "libmonobdwgc": "Unity（Mono）",
    "libflutter.so": "Flutter",
    "libreactnativejni.so": "React Native",
    "libhermes.so": "React Native（Hermes）",
    "libcocos": "Cocos",
    "libgodot": "Godot",
}

# 音声・通信ライブラリの判定に使うネイティブライブラリ名（部分一致）
AUDIO_LIBS = {
    "liboboe": "Oboe",
    "libagora": "Agora",
    "libzego": "ZEGO",
    "libliteav": "Tencent LiteAV",
    "libtrtc": "Tencent TRTC",
    "libjingle_peerconnection": "WebRTC",
    "libwebrtc": "WebRTC",
    "libmediasoup": "mediasoup",
    "librtmp": "RTMP",
    "libsrt": "SRT",
    "libvivox": "Vivox",
    "libphoton": "Photon",
    "libfmod": "FMOD",
    "libwwise": "Wwise",
    "libcri": "CRIWARE",
    "libopus": "Opus",
}

# 改ざん対策・難読化の判定に使うネイティブライブラリ名（部分一致）
PROTECT_LIBS = {
    "libpairipcore": "PairIP（Google Play の自動保護）",
    "libjiagu": "360 加固",
    "libdexhelper": "Bangcle",
    "libsecexe": "Bangcle",
    "libsecshell": "Bangcle",
    "libshell": "汎用パッカー",
    "libprotectclass": "汎用パッカー",
    "libappguard": "NHN AppGuard",
    "libcompatible": "NHN AppGuard（推定）",
    "libnesec": "NetEase 保護",
    "libmsaoaidsec": "MSA 保護",
    "libshield": "Promon SHIELD（推定）",
    "libdexprotector": "DexProtector",
    "libliapp": "LIAPP",
    "libarxan": "Arxan",
}

# DEX 内で探すクラス記述子（DEX の文字列表にそのまま現れる）
DEX_PATTERNS = {
    "Landroid/media/AudioRecord;": "Java の AudioRecord を参照",
    "Landroid/media/AudioRecord$Builder;": "AudioRecord.Builder を参照",
    "Landroid/media/MediaRecorder$AudioSource;": "録音元の定数を参照",
    "Landroid/media/audiofx/AcousticEchoCanceler;": "エコーキャンセラーを参照",
    "Landroid/media/audiofx/NoiseSuppressor;": "ノイズ抑制を参照",
    "Landroid/media/AudioPlaybackCaptureConfiguration;": "再生音キャプチャを参照",
    "Lorg/webrtc/audio/WebRtcAudioRecord;": "WebRTC の Java 録音実装",
    "Lorg/webrtc/voiceengine/WebRtcAudioRecord;": "WebRTC の旧 Java 録音実装",
    "Lio/agora/": "Agora SDK",
    "Lim/zego/": "ZEGO SDK",
    "Lcom/zego/": "ZEGO SDK",
    "Lcom/tencent/liteav/": "Tencent LiteAV SDK",
    "Lcom/tencent/trtc/": "Tencent TRTC SDK",
    "Lcom/unity3d/player/UnityPlayer;": "Unity",
    "Lio/flutter/": "Flutter",
    "Lcom/facebook/react/": "React Native",
    "Lcom/pairip/": "PairIP（Google Play の自動保護）",
    "Lcom/google/android/play/core/integrity/": "Play Integrity API",
    "Lcom/google/android/gms/safetynet/": "SafetyNet",
    "Lcom/google/android/vending/licensing/": "Play ライセンス確認",
    "Lcom/scottyab/rootbeer/": "RootBeer（root 検知）",
    "Lde/robv/android/xposed/": "Xposed への言及",
    "LSPatch": "LSPatch への言及",
    "Ljava/security/MessageDigest;": "ハッシュ計算（署名検証の可能性）",
    "Landroid/content/pm/SigningInfo;": "署名情報の取得（署名検証の可能性）",
}

# ネイティブライブラリ内で探す文字列
NATIVE_PATTERNS = {
    b"AAudioStreamBuilder_setDirection": "AAudio（ネイティブ録音の可能性）",
    b"AAudio_createStreamBuilder": "AAudio",
    b"slCreateEngine": "OpenSL ES",
    b"SL_IID_RECORD": "OpenSL ES の録音",
    b"android/media/AudioRecord": "ネイティブから Java の AudioRecord を呼び出し",
    b"oboe::AudioStreamBuilder": "Oboe",
    b"Microphone": "Microphone という文字列",
}

# Unity の場合にメタデータで探す文字列
UNITY_META_PATTERNS = {
    b"UnityEngine.Microphone": "Unity 標準の Microphone クラス",
    b"Agora": "Agora",
    b"Photon": "Photon",
    b"Vivox": "Vivox",
    b"Dissonance": "Dissonance",
    b"NatDevice": "NatDevice",
    b"CRIWARE": "CRIWARE",
    b"AudioRecord": "AudioRecord",
}


def adb(args, serial=None, check=True):
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += args
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    except FileNotFoundError:
        sys.exit("adb が見つかりません。Android SDK の platform-tools にパスを通してください。")
    if check and res.returncode != 0:
        sys.exit(f"adb の実行に失敗しました: {' '.join(cmd)}\n{res.stderr.strip()}")
    return res.stdout


def ensure_device(serial):
    lines = [l for l in adb(["devices"]).splitlines()[1:] if l.strip()]
    ready = [l.split()[0] for l in lines if l.split()[-1] == "device"]
    if serial:
        if serial not in ready:
            sys.exit(f"指定した端末 {serial} が接続されていません。")
        return serial
    if not ready:
        sys.exit("端末が見つかりません。USB デバッグを有効にし、接続を許可してください。")
    if len(ready) > 1:
        sys.exit("複数の端末が接続されています。--serial で指定してください: " + ", ".join(ready))
    return ready[0]


def find_package(serial, given):
    if given:
        return given
    out = adb(["shell", "pm", "list", "packages"], serial)
    pkgs = [l.split(":", 1)[1].strip() for l in out.splitlines() if "iriam" in l.lower()]
    if not pkgs:
        sys.exit("IRIAM のパッケージが見つかりません。--package で指定してください。")
    if len(pkgs) > 1:
        sys.exit("候補が複数あります。--package で指定してください: " + ", ".join(pkgs))
    return pkgs[0]


def package_info(serial, pkg):
    out = adb(["shell", "dumpsys", "package", pkg], serial)
    info = {}
    for key in ("versionName", "versionCode", "minSdk", "targetSdk", "userId", "installerPackageName"):
        m = re.search(rf"\b{key}=(\S+)", out)
        if m:
            info[key] = m.group(1)
    perms = sorted(set(re.findall(r"(android\.permission\.[A-Z_]+)", out)))
    interesting = [p for p in perms if any(k in p for k in ("AUDIO", "RECORD", "FOREGROUND", "MEDIA", "BLUETOOTH", "CAMERA"))]
    info["permissions"] = interesting
    return info


def pull_apks(serial, pkg, dest):
    dest.mkdir(parents=True, exist_ok=True)
    paths = [l.split(":", 1)[1].strip() for l in adb(["shell", "pm", "path", pkg], serial).splitlines() if l.startswith("package:")]
    local = []
    for p in paths:
        target = dest / Path(p).name
        print(f"取り出し中: {Path(p).name}")
        adb(["pull", p, str(target)], serial)
        local.append(target)
    return local


def scan_bytes(data, patterns):
    return [label_key for label_key in patterns if label_key in data]


def analyze_apks(apks):
    result = {
        "apks": [],
        "libs": set(),
        "engine": set(),
        "audio": set(),
        "protect": set(),
        "dex_hits": {},
        "native_hits": {},
        "unity_meta_hits": set(),
        "markers": set(),
    }
    for apk in apks:
        with zipfile.ZipFile(apk) as z:
            names = z.namelist()
            dex = [n for n in names if re.fullmatch(r"classes\d*\.dex", n)]
            result["apks"].append(f"{apk.name}: エントリ {len(names)} 件、DEX {len(dex)} 個")
            if any(n.startswith("assets/bin/Data/") for n in names):
                result["markers"].add("assets/bin/Data/（Unity のデータ）")
            if any("flutter_assets" in n for n in names):
                result["markers"].add("flutter_assets（Flutter）")
            if "assets/index.android.bundle" in names:
                result["markers"].add("index.android.bundle（React Native）")

            for n in dex:
                data = z.read(n)
                for pat in DEX_PATTERNS:
                    if pat.encode() in data:
                        result["dex_hits"].setdefault(pat, set()).add(n)

            for n in names:
                if n.startswith("lib/") and n.endswith(".so"):
                    lib = n.split("/")[-1]
                    abi = n.split("/")[1]
                    result["libs"].add(f"{abi}/{lib}")
                    low = lib.lower()
                    for key, label in ENGINE_LIBS.items():
                        if key in low:
                            result["engine"].add(label)
                    for key, label in AUDIO_LIBS.items():
                        if key in low:
                            result["audio"].add(f"{label}（{lib}）")
                    for key, label in PROTECT_LIBS.items():
                        if key in low:
                            result["protect"].add(f"{label}（{lib}）")
                    # 64 ビット版のみ中身を走査する（重複を避けるため）
                    if abi == "arm64-v8a":
                        data = z.read(n)
                        for pat in NATIVE_PATTERNS:
                            if pat in data:
                                result["native_hits"].setdefault(pat, set()).add(lib)

                if n.endswith("global-metadata.dat"):
                    data = z.read(n)
                    for pat in UNITY_META_PATTERNS:
                        if pat in data:
                            result["unity_meta_hits"].add(pat)
    return result


def format_static_report(pkg, info, res):
    lines = ["# IRIAM 静的解析レポート", ""]
    lines.append(f"パッケージ名: {pkg}")
    for key in ("versionName", "versionCode", "minSdk", "targetSdk", "installerPackageName"):
        if key in info:
            lines.append(f"{key}: {info[key]}")
    lines.append("関連する権限: " + (", ".join(info.get("permissions", [])) or "なし"))
    lines.append("")
    lines.append("## APK")
    lines += [f"- {a}" for a in res["apks"]]
    lines.append("")
    lines.append("## 実装基盤")
    lines += [f"- {e}" for e in sorted(res["engine"] | res["markers"])] or ["- 判定できず"]
    lines.append("")
    lines.append("## 音声・通信ライブラリ")
    lines += [f"- {a}" for a in sorted(res["audio"])] or ["- 該当なし"]
    lines.append("")
    lines.append("## 改ざん対策・難読化（ライブラリ名から推定）")
    lines += [f"- {p}" for p in sorted(res["protect"])] or ["- 該当なし"]
    lines.append("")
    lines.append("## DEX 内の参照")
    if res["dex_hits"]:
        for pat, files in sorted(res["dex_hits"].items()):
            lines.append(f"- {DEX_PATTERNS[pat]}: {pat}（{', '.join(sorted(files))}）")
    else:
        lines.append("- 該当なし")
    lines.append("")
    lines.append("## ネイティブライブラリ内の参照（arm64-v8a）")
    if res["native_hits"]:
        for pat, libs in sorted(res["native_hits"].items()):
            lines.append(f"- {NATIVE_PATTERNS[pat]}: {pat.decode()}（{', '.join(sorted(libs))}）")
    else:
        lines.append("- 該当なし")
    if res["unity_meta_hits"]:
        lines.append("")
        lines.append("## Unity メタデータ内の参照")
        lines += [f"- {UNITY_META_PATTERNS[p]}: {p.decode()}" for p in sorted(res["unity_meta_hits"])]
    lines.append("")
    lines.append("## 同梱ネイティブライブラリ一覧")
    lines += [f"- {l}" for l in sorted(res["libs"])] or ["- なし"]
    return "\n".join(lines) + "\n"


def cmd_static(args):
    serial = ensure_device(args.serial)
    pkg = find_package(serial, args.package)
    print(f"対象パッケージ: {pkg}")
    info = package_info(serial, pkg)
    apks = pull_apks(serial, pkg, OUT_DIR / "apk")
    print("解析中です。大きなライブラリがあると数分かかります。")
    res = analyze_apks(apks)
    report = format_static_report(pkg, info, res)
    path = OUT_DIR / "report_static.txt"
    path.write_text(report, encoding="utf-8")
    print()
    print(report)
    print(f"レポートを保存しました: {path}")


def extract_input_threads(flinger):
    """audio_flinger の出力から入力スレッドの節を抜き出す。"""
    blocks = re.split(r"\n(?=\S[^\n]*\bthread 0x)", flinger)
    picked = []
    for b in blocks:
        head = b.strip().splitlines()[0] if b.strip() else ""
        if re.search(r"\bthread 0x", head) and re.search(r"Input|RECORD|MMAP_CAPTURE", head):
            picked.append(b)
    return picked


def cmd_runtime(args):
    serial = ensure_device(args.serial)
    pkg = find_package(serial, args.package)
    info = package_info(serial, pkg)
    uid = info.get("userId", "")
    print(f"対象パッケージ: {pkg}（uid {uid or '不明'}）")
    print()
    print("IRIAM でマイクが動作している状態にしてください。")
    print("配信前の準備画面でマイクの音量表示が動いていれば十分です。")
    print("必要に応じて USB マイクを接続した状態でも実施してください。")
    input("準備ができたら Enter を押してください。")

    raw = {}
    for name, cmd in (
        ("audio", ["shell", "dumpsys", "audio"]),
        ("audio_flinger", ["shell", "dumpsys", "media.audio_flinger"]),
        ("audio_policy", ["shell", "dumpsys", "media.audio_policy"]),
    ):
        raw[name] = adb(cmd, serial, check=False)
        (OUT_DIR).mkdir(parents=True, exist_ok=True)
        (OUT_DIR / f"raw_{name}.txt").write_text(raw[name], encoding="utf-8")

    keys = [pkg] + ([uid] if uid else [])
    lines = ["# IRIAM 実行時の音声経路レポート", "", f"パッケージ名: {pkg}", f"uid: {uid or '不明'}", ""]

    lines.append("## dumpsys audio のうち対象アプリに関する行")
    hits = [l.strip() for l in raw["audio"].splitlines() if any(k in l for k in keys)]
    lines += [f"- {h}" for h in hits[:80]] or ["- 該当なし（マイクが動作していない可能性があります）"]
    lines.append("")

    lines.append("## audio_policy のうち録音に関する行")
    policy_hits = [
        l.strip() for l in raw["audio_policy"].splitlines()
        if any(k in l for k in keys) or re.search(r"AUDIO_SOURCE|source:|IN_USB|USB_DEVICE|USB_HEADSET", l)
    ]
    lines += [f"- {h}" for h in policy_hits[:80]] or ["- 該当なし"]
    lines.append("")

    lines.append("## audio_flinger の入力スレッド")
    threads = extract_input_threads(raw["audio_flinger"])
    if threads:
        for t in threads[:6]:
            lines.append("```")
            lines += t.strip().splitlines()[:60]
            lines.append("```")
    else:
        lines.append("- 入力スレッドを抽出できませんでした。raw_audio_flinger.txt を確認してください。")
    lines.append("")

    lines.append("## 音声エフェクトに関する行")
    fx = [
        l.strip() for l in raw["audio_flinger"].splitlines()
        if re.search(r"Echo|AEC|Noise Suppress|NS\b|Automatic Gain|AGC|Effect ID|effect.*name", l, re.I)
    ]
    lines += [f"- {f}" for f in fx[:60]] or ["- 該当なし"]

    report = "\n".join(lines) + "\n"
    path = OUT_DIR / "report_runtime.txt"
    path.write_text(report, encoding="utf-8")
    print()
    print(report)
    print(f"レポートを保存しました: {path}")
    print("共有前に、他のアプリ名など見せたくない情報が含まれていないか確認してください。")


def main():
    parser = argparse.ArgumentParser(description="IRIAM の音声取得経路を調べる Phase 0 用スクリプト")
    parser.add_argument("mode", choices=["static", "runtime"], help="static: APK の静的解析 / runtime: 実行時の音声経路の取得")
    parser.add_argument("--package", help="パッケージ名（省略時は iriam を含むものを自動検出）")
    parser.add_argument("--serial", help="端末のシリアル番号（複数台接続時のみ必要）")
    args = parser.parse_args()
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    if args.mode == "static":
        cmd_static(args)
    else:
        cmd_runtime(args)


if __name__ == "__main__":
    main()
