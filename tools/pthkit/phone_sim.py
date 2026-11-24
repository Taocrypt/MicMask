"""手机端算法的 Python 参考实现：pth + 模板 → 校准 → QDQ ONNX（字节拼接）"""
import sys, json, numpy as np, torch, onnx, onnxruntime as ort
from onnx import TensorProto
exec(open('/workspace/work/pthtpl/sim.py').read())
def run(pth, tpl, out, ncal=8):
    J = json.load(open(tpl + '.json')); w = torch.load(pth, map_location='cpu', weights_only=False)['weight']
    def calc(r):
        if r['op'] == 'wn':
            g = w[r['src']+'_g'].float().numpy().astype(np.float64); v = w[r['src']+'_v'].float().numpy().astype(np.float64)
            n = np.sqrt((v**2).reshape(v.shape[0], -1).sum(1)).reshape((-1,)+(1,)*(v.ndim-1)); a = g*v/n
        else:
            a = w[r['src']].float().numpy()
            if r['op'] == 'T': a = a.T
            if r['op'] == 'row0': a = a[0]
        return np.ascontiguousarray(a, dtype=np.float32).reshape(r['dims'])
    def tp(name, arr, dt):
        t = onnx.TensorProto(name=name, data_type=dt, dims=list(arr.shape), raw_data=np.ascontiguousarray(arr).tobytes())
        m = onnx.ModelProto(); m.graph.initializer.append(t); return m.SerializeToString()
    W = {r['name']: calc(r) for r in J['recipe']}
    # 1) fp32 校准模型：模板 + 权重 + 额外输出
    acts = [p['name'] for p in J['plan'] if p['kind'] == 'act']
    buf = open(tpl + '_f32.onnx', 'rb').read()
    for n, a in W.items(): buf += tp(n, a, TensorProto.FLOAT)
    for a in acts:
        m = onnx.ModelProto(); m.graph.output.append(onnx.ValueInfoProto(name=a)); buf += m.SerializeToString()
    so = ort.SessionOptions(); so.log_severity_level = 3
    s = ort.InferenceSession(buf, so); names = [o.name for o in s.get_outputs()]
    z = np.load('/workspace/work/micmask-repo/tools/calib.npz'); ph = z['phone9'] if J['emb'] == 256 else z['phone']
    mn = {}; mx = {}
    for i in range(ncal):
        o = s.run(None, dict(phone=ph[i:i+1].astype(np.float32), pitch=z['pitch'][i:i+1], pitchf=z['pitchf'][i:i+1]))
        for n, x in zip(names, o):
            mn[n] = min(mn.get(n, np.inf), float(x.min())); mx[n] = max(mx.get(n, -np.inf), float(x.max()))
    AS = {}
    buf = open(tpl + '_qdq.onnx', 'rb').read()
    for a in acts:
        sc, zp = actq(mn[a], mx[a]); AS[a] = sc
        buf += tp(a+'_scale', np.array(sc, np.float32), TensorProto.FLOAT) + tp(a+'_zero_point', np.array(zp, np.uint8), TensorProto.UINT8)
    WS = {}
    for p in J['plan']:
        if p['kind'] == 'f32': buf += tp(p['name'], W[p['name']], TensorProto.FLOAT)
        elif p['kind'] == 'w':
            q, sc = qweight(W[p['name']], p['axis']); WS[p['name']] = sc
            buf += tp(p['name']+'_quantized', q, TensorProto.INT8) + tp(p['name']+'_scale', sc, TensorProto.FLOAT) + tp(p['name']+'_zero_point', np.zeros(sc.shape, np.int8), TensorProto.INT8)
    for p in J['plan']:
        if p['kind'] == 'bias':
            bs = (AS[p['act']] * WS[p['w']]).astype(np.float32); bs[bs == 0] = 1e-8
            q = np.round(W[p['name']] / bs).astype(np.int32)
            buf += tp(p['name']+'_quantized', q, TensorProto.INT32) + tp(p['name']+'_quantized_scale', bs, TensorProto.FLOAT) + tp(p['name']+'_quantized_zero_point', np.zeros(bs.shape, np.int32), TensorProto.INT32)
    open(out, 'wb').write(buf); return out
if __name__ == '__main__':
    pth, tpl, out = sys.argv[1:4]; run(pth, tpl, out, int(__import__("os").environ.get("NCAL","8")))
    so = ort.SessionOptions(); so.log_severity_level = 3
    z = np.load('/workspace/work/micmask-repo/tools/calib.npz')
    def spec(x):
        x = x.ravel(); fr = np.lib.stride_tricks.sliding_window_view(x, 1024)[::256] * np.hanning(1024)
        return np.log(np.abs(np.fft.rfft(fr, axis=1)) + 1e-4)
    def snr(ref, x): return float(np.abs(spec(ref) - spec(x)).mean())
    for i in (10, 20, 33, 40, 47, 52):
        feed = dict(phone=z['phone'][i:i+1].astype(np.float32), pitch=z['pitch'][i:i+1], pitchf=z['pitchf'][i:i+1])
        r = [ort.InferenceSession(f, so).run(None, feed)[0] for f in [out] + sys.argv[4:]]
        print(i, ['%.3f' % snr(r[-1], x) for x in r[:-1]])
