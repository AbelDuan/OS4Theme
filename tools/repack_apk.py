#!/usr/bin/env python3
"""就地重打包 APK：只替换 classes.dex 与 META-INF/xposed/*，其余资源/清单原样保留。

为什么需要它：本机是 arm64，而 Google 的 build-tools 里 aapt2 / zipalign 是 x86_64
二进制（跑不了）。这两个工具在这里的替代品：
  - aapt2：不需要 —— 资源与 AndroidManifest 直接沿用基线 APK；
  - zipalign：实现等价的最小对齐（STORED 条目按 4 字节对齐，写 padding extra field），
    满足 Android 11+ 对 resources.arsc 未压缩且 4 字节对齐的要求。

签名仍用官方 apksigner（Java 程序，arm64 可运行）。
"""
import argparse
import shutil
import zipfile

ALIGN = 4


def repack(base, dex, meta_dir, out, replacements):
    src = zipfile.ZipFile(base)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            name = info.filename
            if name in replacements:
                data = open(replacements[name], "rb").read()
                ctype = zipfile.ZIP_DEFLATED
            else:
                data = src.read(name)
                ctype = info.compress_type

            zi = zipfile.ZipInfo(name, date_time=info.date_time)
            zi.compress_type = ctype
            zi.external_attr = info.external_attr
            zi.internal_attr = info.internal_attr
            zi.create_system = info.create_system

            extra = b""
            if ctype == zipfile.ZIP_STORED:
                pos = dst.fp.tell()
                base_len = pos + 30 + len(name.encode("utf-8"))
                pad = (-base_len) % ALIGN
                if pad and pad < 4:
                    pad += ALIGN
                if pad:
                    extra = b"\x00\x00" + (pad - 2).to_bytes(2, "little") + b"\x00" * (pad - 4)
            zi.extra = extra
            dst.writestr(zi, data)
    src.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True, help="基线 APK（已装版本的副本）")
    ap.add_argument("--dex", required=True, help="新编译出的 classes.dex")
    ap.add_argument("--meta", required=True, help="META-INF/xposed 源目录")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    reps = {"classes.dex": a.dex}
    for f in ("module.prop", "scope.list", "java_init.list"):
        reps[f"META-INF/xposed/{f}"] = f"{a.meta}/{f}"
    repack(a.base, a.dex, a.meta, a.out, reps)
    print("wrote", a.out)
    with zipfile.ZipFile(a.out) as z:
        for i in z.infolist():
            if i.filename in reps or i.filename == "resources.arsc":
                print(f"  {i.filename:38s} method={i.compress_type} size={i.file_size}")


if __name__ == "__main__":
    main()
