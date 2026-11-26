#!/usr/bin/env python3
"""原生声码器离线精度/耗时对比（主机端，不需要手机）。

先用 cli 的 VocTest 拆分模型：
  java -cp <cli/build/install/cli/lib/*> micmask.cli.VocTestKt <模型.onnx> <目录>
     → <目录>/pre.onnx（ORT 前半图）、check.onnx（原图 + z/har/cond 额外输出）、dec.mmvoc（fp16 权重）、names.txt
再编译主机版 voc_cli（见 README.md），然后：
  python3 compare.py <目录> <voc_cli> [calib.npz] [--threads 4]

比较三样东西（同一组 z / har 输入）：
  ort   : check.onnx 的原始输出 audio（ORT 整图，dec 带 QDQ 激活量化 —— 即现在手机上跑的结果）
  ref32 : 独立的 PyTorch fp32 参考实现（直接从 ONNX 读权重并反量化，不经过 Kotlin 提取代码）
  neon16/neon32 : 原生声码器 fp16 / fp32 内核
依赖：numpy、onnx、onnxruntime、torch（CPU）。
"""
import sys, os, json, time, subprocess, argparse
import numpy as np
import onnx
from onnx import numpy_helper
import onnxruntime as ort
import torch
import torch.nn.functional as F

ap = argparse.ArgumentParser()
ap.add_argument('dir'); ap.add_argument('cli'); ap.add_argument('calib', nargs='?')
ap.add_argument('--threads', type=int, default=4); ap.add_argument('--n', type=int, default=4)
A = ap.parse_args()
D = A.dir
zname, hname, cname = (open(os.path.join(D, 'names.txt')).read().split('\n') + ['', '', ''])[:3]

# ---------- 输入：真实人声特征（calib.npz）或随机 ----------
def inputs():
    if A.calib and os.path.exists(A.calib):
        z = np.load(A.calib)
        for i in range(0, len(z['pitch']), max(1, len(z['pitch']) // A.n))[:A.n]:
            yield dict(phone=z['phone'][i:i + 1].astype(np.float32), pitch=z['pitch'][i:i + 1].astype(np.int64),
                       pitchf=z['pitchf'][i:i + 1].astype(np.float32))
    else:
        rng = np.random.default_rng(0)
        for i in range(A.n):
            f0 = (180 + 60 * np.sin(np.arange(100) / 9 + i)).astype(np.float32)
            f0[(np.arange(100) // 17 + i) % 4 == 0] = 0
            pitch = np.clip(np.round(1127 * np.log(1 + f0 / 700) * 0 + (f0 > 0) * 100), 1, 255).astype(np.int64)
            yield dict(phone=rng.standard_normal((1, 100, 768)).astype(np.float32), pitch=pitch[None], pitchf=f0[None])

so = ort.SessionOptions(); so.intra_op_num_threads = A.threads
check = ort.InferenceSession(os.path.join(D, 'check.onnx'), so, providers=['CPUExecutionProvider'])
pre = ort.InferenceSession(os.path.join(D, 'pre.onnx'), so, providers=['CPUExecutionProvider'])
emb = check.get_inputs()[0].shape[2]
outs = [o.name for o in check.get_outputs()]

# ---------- 独立 fp32 参考：从 ONNX 读 dec 权重 ----------
m = onnx.load(os.path.join(D, 'check.onnx'))
g = m.graph
init = {i.name: numpy_helper.to_array(i) for i in g.initializer}
prod = {o: n for n in g.node for o in n.output}
byname = {n.name: n for n in g.node}
def attr(n, k, d=None):
    for a in n.attribute:
        if a.name == k: return onnx.helper.get_attribute_value(a)
    return d
def W(name):
    if name in init: return init[name].astype(np.float32)
    n = prod[name]; assert n.op_type == 'DequantizeLinear', (name, n.op_type)
    q = init[n.input[0]].astype(np.float64); s = init[n.input[1]].astype(np.float64)
    zp = init[n.input[2]].astype(np.float64) if len(n.input) > 2 and n.input[2] else np.zeros_like(s)
    ax = attr(n, 'axis', 1)
    if s.size > 1:
        sh = [1] * q.ndim; sh[ax] = -1; s = s.reshape(sh); zp = zp.reshape(sh)
    return ((q - zp) * s).astype(np.float32)
def cval(name):
    if name in init: return init[name]
    n = prod[name]
    if n.op_type == 'Constant': return numpy_helper.to_array(attr(n, 'value'))
    if n.op_type in ('Unsqueeze', 'Reshape', 'Squeeze', 'Identity', 'Cast'): return np.asarray(cval(n.input[0])).reshape(-1)
    raise ValueError(name)
def node(suffix):
    r = [n for n in g.node if n.name.endswith(suffix)]
    return r[0] if r else None
def conv(x, n, transpose=False):
    w = torch.from_numpy(W(n.input[1])); b = torch.from_numpy(W(n.input[2])) if len(n.input) > 2 and n.input[2] else None
    st = attr(n, 'strides', [1])[0]; p = attr(n, 'pads', [0, 0])[0]; d = attr(n, 'dilations', [1])[0]
    if transpose: return F.conv_transpose1d(x, w, b, stride=st, padding=p)
    return F.conv1d(x, w, b, stride=st, padding=p, dilation=d)
ups = []
while node(f'/dec/ups.{len(ups)}/ConvTranspose'): ups.append(node(f'/dec/ups.{len(ups)}/ConvTranspose'))
nres = len({n.name.split('/')[2] for n in g.node if n.name.startswith('/dec/resblocks.')})
nk = nres // len(ups)
rs = [n for n in g.node if n.op_type == 'ConvTranspose' and not n.name.startswith('/dec/')]

def ref32(z, har, cond):
    with torch.no_grad():
        x = conv(torch.from_numpy(z), node('/dec/conv_pre/Conv'))
        if cond is not None: x = x + torch.from_numpy(cond)
        h = torch.from_numpy(har).reshape(1, 1, -1)
        for i, u in enumerate(ups):
            x = F.leaky_relu(x, 0.1); x = conv(x, u, True)
            x = x + conv(h, node(f'/dec/noise_convs.{i}/Conv'))
            xs = None
            for j in range(nk):
                r = i * nk + j; y = x; mm = 0
                while node(f'/dec/resblocks.{r}/convs1.{mm}/Conv'):
                    t = conv(F.leaky_relu(y, 0.1), node(f'/dec/resblocks.{r}/convs1.{mm}/Conv'))
                    t = conv(F.leaky_relu(t, 0.1), node(f'/dec/resblocks.{r}/convs2.{mm}/Conv')); y = y + t; mm += 1
                while node(f'/dec/resblocks.{r}/convs.{mm}/Conv'):
                    y = y + conv(F.leaky_relu(y, 0.1), node(f'/dec/resblocks.{r}/convs.{mm}/Conv')); mm += 1
                xs = y if xs is None else xs + y
            x = xs / nk
        x = torch.tanh(conv(F.leaky_relu(x, 0.01), node('/dec/conv_post/Conv')))
        o = x.reshape(-1)
        L = z.shape[2]
        if rs:
            n = rs[0]; up = attr(n, 'strides')[0]; h_ = torch.from_numpy(init[n.input[1]].copy())
            u = F.conv_transpose1d(o.view(1, 1, -1), h_, stride=up)
            sl = [c for c in g.node if c.op_type == 'Slice' and n.output[0] in c.input][0]
            off = int(np.asarray(cval(sl.input[1])).reshape(-1)[0]); dn = int(np.asarray(cval(sl.input[4])).reshape(-1)[0]) if len(sl.input) > 4 else 1
            o = u.reshape(-1)[off:off + o.numel() * up:dn]
        return o[:L * 480].numpy()

def snr(ref, x):
    n = min(len(ref), len(x)); ref = ref[:n].astype(np.float64); x = x[:n].astype(np.float64)
    e = np.sum((ref - x) ** 2); return 10 * np.log10(np.sum(ref ** 2) / max(e, 1e-30)), float(np.max(np.abs(ref - x)))

res = []
tmp = os.path.join(D, '_cmp'); os.makedirs(tmp, exist_ok=True)
for k, feed in enumerate(inputs()):
    if feed['phone'].shape[2] != emb: feed['phone'] = feed['phone'][..., :emb]
    r = dict(zip(outs, check.run(None, feed)))
    audio = r[[o for o in outs if o not in (zname, hname, cname)][0]].reshape(-1); z = r[zname]; har = r[hname]; cond = r[cname] if cname else None
    L = z.shape[2]
    z.astype(np.float32).tofile(f'{tmp}/z.f32'); har.astype(np.float32).tofile(f'{tmp}/har.f32')
    if cond is not None: cond.astype(np.float32).reshape(-1).tofile(f'{tmp}/cond.f32')
    row = dict(case=k, L=L, rms=float(np.sqrt(np.mean(audio ** 2))))
    ref = ref32(z, har, cond)
    row['ref32_vs_ort'] = snr(ref, audio)
    for fp in (1, 0):
        out = f'{tmp}/out{fp}.f32'
        p = subprocess.run([A.cli, f'{D}/dec.mmvoc', f'{tmp}/z.f32', f'{tmp}/har.f32', f'{tmp}/cond.f32' if cond is not None else '-',
                            str(L), out, str(fp), str(A.threads), '5'], capture_output=True, text=True)
        if p.returncode: print(p.stdout, p.stderr); sys.exit(1)
        y = np.fromfile(out, np.float32)
        tag = 'neon16' if fp else 'neon32'
        row[f'{tag}_vs_ref32'] = snr(ref, y); row[f'{tag}_vs_ort'] = snr(audio, y)
        row[f'{tag}_ms'] = [l for l in p.stdout.splitlines() if l.startswith('run')][0]
        if k == 0: row[f'{tag}_desc'] = [l for l in p.stdout.splitlines() if l.startswith('decoder')][0]
    res.append(row)
    print(json.dumps(row, ensure_ascii=False))

# ---------- ORT 耗时：整图 vs 前半图 ----------
feed = next(iter(inputs()))
if feed['phone'].shape[2] != emb: feed['phone'] = feed['phone'][..., :emb]
full = ort.InferenceSession(os.path.join(D, 'check.onnx'), so, providers=['CPUExecutionProvider'])
def bench(s):
    s.run(None, feed); t = time.perf_counter()
    for _ in range(5): s.run(None, feed)
    return (time.perf_counter() - t) / 5 * 1000
print(json.dumps(dict(ort_full_ms=round(bench(full), 1), ort_pre_ms=round(bench(pre), 1), threads=A.threads)))
