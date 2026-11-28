#!/usr/bin/env python3
"""Writes the third-party license bundle for one plugin build.

usage (in plugins/tailscale/native, with GOOS/GOARCH/CGO_ENABLED set like the build):
    collect_licenses.py OUT.txt

Lists every Go module linked into the library (go list -deps) and appends each
module's LICENSE / COPYING / NOTICE files verbatim. Ship OUT.txt next to the
binary (BSD-3-Clause and Apache-2.0 require the notices with binary copies).
"""
import json, os, subprocess, sys

out = sys.argv[1]
raw = subprocess.check_output(['go', 'list', '-deps', '-json', '.'], text=True)
dec, i, mods = json.JSONDecoder(), 0, {}
while i < len(raw):
    while i < len(raw) and raw[i].isspace():
        i += 1
    if i >= len(raw):
        break
    obj, i = dec.raw_decode(raw, i)
    m = obj.get('Module')
    if obj.get('Standard') or not m or m.get('Main'):
        continue
    mods[m['Path']] = (m.get('Version', ''), m.get('Dir', ''))
goroot = subprocess.check_output(['go', 'env', 'GOROOT'], text=True).strip()
goversion = subprocess.check_output(['go', 'env', 'GOVERSION'], text=True).strip()
names = ('LICENSE', 'LICENCE', 'COPYING', 'NOTICE', 'PATENTS')
with open(out, 'w', encoding='utf-8') as f:
    f.write('MicMask Tailscale plugin - third-party notices\n')
    f.write('Modules linked into this library (Go module path, version):\n\n')
    f.write(f'  Go standard library {goversion}\n')
    for p in sorted(mods):
        f.write(f'  {p} {mods[p][0]}\n')
    def dump(title, d):
        if not d or not os.path.isdir(d):
            f.write(f'\n==== {title}: license files not found ====\n')
            return
        hit = False
        for n in sorted(os.listdir(d)):
            if n.upper().startswith(names) and os.path.isfile(os.path.join(d, n)):
                hit = True
                f.write(f'\n==== {title} - {n} ====\n\n')
                f.write(open(os.path.join(d, n), encoding='utf-8', errors='replace').read())
        if not hit:
            f.write(f'\n==== {title}: no license file at module root ====\n')
    dump(f'Go standard library {goversion}', goroot)
    for p in sorted(mods):
        dump(f'{p} {mods[p][0]}', mods[p][1])
print(f'{len(mods)} modules -> {out}')
