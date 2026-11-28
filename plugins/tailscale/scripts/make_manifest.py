#!/usr/bin/env python3
"""Writes the plugin manifest.json.

usage: make_manifest.py --version V --tailscale TSVER --base-url URL --out manifest.json \
           [--mirror PREFIX ...] platform=path [platform=path ...]

Each asset's url is <base-url>/<file name>; every --mirror PREFIX adds an extra
url <PREFIX><primary url> after the primary (e.g. a GitHub proxy for users in
mainland China). sha256 and size are computed from the files.
"""
import argparse, hashlib, json, os

ap = argparse.ArgumentParser()
ap.add_argument('--version', required=True)
ap.add_argument('--tailscale', required=True)
ap.add_argument('--base-url', required=True)
ap.add_argument('--out', required=True)
ap.add_argument('--mirror', action='append', default=[])
ap.add_argument('--abi', type=int, default=1)
ap.add_argument('assets', nargs='+')
a = ap.parse_args()

plats = {}
for spec in a.assets:
    plat, path = spec.split('=', 1)
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    name = os.path.basename(path)
    url = a.base_url.rstrip('/') + '/' + name
    plats[plat] = {
        'file': name,
        'url': url,
        'urls': [url] + [m + url for m in a.mirror],
        'sha256': h.hexdigest(),
        'size': os.path.getsize(path),
    }
m = {'schema': 1, 'name': 'micmask-tailscale', 'version': a.version, 'abi': a.abi,
     'tailscale': a.tailscale, 'license': 'BSD-3-Clause', 'platforms': plats}
with open(a.out, 'w') as f:
    json.dump(m, f, indent=2)
    f.write('\n')
print(json.dumps(m, indent=2))
