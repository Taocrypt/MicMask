import json, glob, os, numpy as np, sys
K = sys.argv[1]
for j in glob.glob(f'{K}/*.json'):
    J = json.load(open(j)); L = []
    for r in J['recipe']: L.append(f"R\t{r['name']}\t{r['src']}\t{r['op']}\t{','.join(map(str, r['dims']))}")
    for p in J['plan']:
        if p['kind'] == 'w': L.append(f"W\t{p['name']}\t{p['axis']}")
        elif p['kind'] == 'bias': L.append(f"B\t{p['name']}\t{p['act']}\t{p['w']}")
        elif p['kind'] == 'act': L.append(f"A\t{p['name']}")
        elif p['kind'] == 'f32': L.append(f"F\t{p['name']}")
    open(j[:-5] + '.plan', 'w').write('\n'.join(L) + '\n')
z = np.load('/workspace/work/micmask-repo/tools/calib.npz'); n = 8
with open(f'{K}/cal.bin', 'wb') as f:
    f.write(np.int32(n).tobytes())
    f.write(z['phone'][:n].astype('<f2').tobytes()); f.write(z['phone9'][:n].astype('<f2').tobytes())
    f.write(z['pitch'][:n].astype('<i2').tobytes()); f.write(z['pitchf'][:n].astype('<f4').tobytes())
print('ok', os.path.getsize(f'{K}/cal.bin'))
