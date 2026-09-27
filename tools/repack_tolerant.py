#!/usr/bin/env python3
"""就地重打包 APK（容错版）。

与 src/tools/repack_apk.py 的区别：
  - 读取端手写中央目录解析，不用 zipfile.ZipFile 读基线 —— 作者发布的 v3.36 APK
    里有一个畸形 extra field（id=0x0000），python 的 zipfile 会直接拒绝打开；
  - 写入端用 zipalign 同款的 0xd935 对齐 extra field（合法且被 Android 忽略），
    保证 STORED 条目（resources.arsc）4 字节对齐。

只替换 classes.dex 与 META-INF/xposed/*，其余条目原样搬运。
"""
import argparse
import struct
import zipfile
import zlib

ALIGN = 4


def _dos_to_tuple(dos_date, dos_time):
    return (
        ((dos_date >> 9) & 0x7F) + 1980,
        (dos_date >> 5) & 0x0F,
        dos_date & 0x1F,
        (dos_time >> 11) & 0x1F,
        (dos_time >> 5) & 0x3F,
        (dos_time & 0x1F) * 2,
    )


def read_entries(path):
    data = open(path, "rb").read()
    eocd = data.rfind(b"PK\x05\x06")
    if eocd < 0:
        raise SystemExit("no EOCD")
    cd_off = struct.unpack_from("<I", data, eocd + 16)[0]
    p = cd_off
    entries = []
    while data[p:p + 4] == b"PK\x01\x02":
        (_sig, _vm, _vn, flags, method, mtime, mdate, _crc, csize, usize,
         nlen, elen, clen, _ds, iattr, eattr, local) = struct.unpack_from("<IHHHHHHIIIHHHHHII", data, p)
        name = data[p + 46:p + 46 + nlen].decode("utf-8", "replace")
        extra = data[p + 46 + nlen:p + 46 + nlen + elen]
        e = dict(name=name, flags=flags, method=method, date=_dos_to_tuple(mdate, mtime),
                 csize=csize, usize=usize, local=local, iattr=iattr, eattr=eattr, extra=extra)
        entries.append(e)
        p += 46 + nlen + elen + clen
    for e in entries:
        lo = e["local"]
        (_lsig, _lvm, lflags, lmethod, _lmt, _lmd, _lcrc, lcs, lus,
         lnl, lel) = struct.unpack_from("<IHHHHHIIIHH", data, lo)
        start = lo + 30 + lnl + lel
        csize = e["csize"] or lcs
        raw = data[start:start + csize]
        e["csize_actual"] = len(raw)
        if e["method"] == 0:
            e["data"] = raw
        elif e["method"] == 8:
            e["data"] = zlib.decompress(raw, -15)
        else:
            raise SystemExit("unsupported method %d for %s" % (e["method"], e["name"]))
    return entries


def repack(base, out, replacements):
    entries = read_entries(base)
    with zipfile.ZipFile(out, "w") as dst:
        for e in entries:
            name = e["name"]
            if name in replacements:
                data = open(replacements[name], "rb").read()
                method = zipfile.ZIP_DEFLATED
            else:
                data = e["data"]
                method = e["method"]
            zi = zipfile.ZipInfo(name, date_time=e["date"])
            zi.compress_type = method
            zi.external_attr = e["eattr"]
            zi.internal_attr = e["iattr"]
            zi.create_system = 3
            if method == zipfile.ZIP_STORED:
                pos = dst.fp.tell()
                base_len = pos + 30 + len(name.encode("utf-8")) + 6
                pad = (-base_len) % ALIGN
                zi.extra = struct.pack("<HHH", 0xD935, 2, ALIGN) + b"\x00" * pad
            dst.writestr(zi, data)
    return entries


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--dex", required=True)
    ap.add_argument("--meta", required=True, help="META-INF/xposed 源目录")
    a = ap.parse_args()
    reps = {"classes.dex": a.dex}
    for f in ("module.prop", "scope.list", "java_init.list"):
        reps["META-INF/xposed/" + f] = "%s/%s" % (a.meta, f)
    entries = repack(a.base, a.out, reps)
    print("wrote", a.out)
    print("base entries:", len(entries))
    with zipfile.ZipFile(a.out) as z:
        for i in z.infolist():
            if i.filename in reps or i.filename == "resources.arsc":
                extra_ok = ""
                if i.compress_type == zipfile.ZIP_STORED:
                    pass
                print("  %-34s method=%d size=%d" % (i.filename, i.compress_type, i.file_size))
        # 校验 resources.arsc 的 4 字节对齐
        import zipfile as _z
        info = z.getinfo("resources.arsc")
        off = z.fp.tell()
        z.fp.seek(0)
        raw = z.fp.read()
        loc = z.getinfo("resources.arsc").header_offset
        (nlen, elen) = struct.unpack_from("<HH", raw, loc + 26)
        data_off = loc + 30 + nlen + elen
        print("  resources.arsc stored=%s data_offset=%d aligned=%s" % (
            info.compress_type == 0, data_off, data_off % ALIGN == 0))


if __name__ == "__main__":
    main()
