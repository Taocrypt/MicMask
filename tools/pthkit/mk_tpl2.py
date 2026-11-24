"""生成「手机导入 .pth」用的流式合成器模板：图结构 + 权重配方（recipe），不含任何模型权重。
手机端解析 .pth 后按配方算出每个初始化器，按 protobuf 合并规则追加到模板字节后面即得完整 ONNX。"""
import sys, os, json, math, torch, numpy as np, onnx
sys.path.insert(0, '/workspace/work/rvcexp/rvc')
from infer.module.models import SynthesizerTrnMs768NSFsid, SynthesizerTrnMs256NSFsid
OUT = sys.argv[1] if len(sys.argv) > 1 else '/workspace/work/pthtpl/out'
os.makedirs(OUT, exist_ok=True)
T = 100
def cfg_of(ver, sr):
    d = json.load(open(f'/workspace/work/rvcexp/rvc/configs/{"v1" if (ver=="v1" or sr==40000) else "v2"}/{sr//1000}k.json'))
    m, dt = d['model'], d['data']
    return [dt['filter_length']//2+1, d['train']['segment_size']//dt['hop_length'], m['inter_channels'], m['hidden_channels'], m['filter_channels'],
            m['n_heads'], m['n_layers'], m['kernel_size'], m['p_dropout'], m['resblock'], m['resblock_kernel_sizes'], m['resblock_dilation_sizes'],
            m['upsample_rates'], m['upsample_initial_channel'], m['upsample_kernel_sizes'], m['spk_embed_dim'], m['gin_channels'], dt['sampling_rate']]
def build(ver, sr, L):
    cfg = cfg_of(ver, sr); EMB = 768 if ver == 'v2' else 256
    Net = SynthesizerTrnMs768NSFsid if ver == 'v2' else SynthesizerTrnMs256NSFsid
    torch.manual_seed(1)
    net = Net(*cfg, is_half=False); del net.enc_q
    sd = {k: torch.randn_like(v) * 0.05 for k, v in net.state_dict().items()}
    for k in sd:
        if k.endswith('weight_g'): sd[k] = sd[k].abs() + 0.5
    net.load_state_dict(sd); net.eval()
    for mm in net.modules():
        if hasattr(mm, 'remove_weight_norm'):
            try: mm.remove_weight_norm()
            except Exception: pass
    # 采样率统一 48k（同 convert_rvc.py）
    g = math.gcd(48000, sr); UP, DN = 48000//g, sr//g; rs = None
    if UP != DN:
        taps = UP*24; n = np.arange(taps)-(taps-1)/2; fc = 0.5/max(UP, DN)*0.92
        h = 2*fc*np.sinc(2*fc*n)*np.kaiser(taps, 8.0)*UP
        rs = torch.nn.ConvTranspose1d(1, 1, taps, stride=UP, bias=False); rs.weight.data = torch.tensor(h, dtype=torch.float32).view(1, 1, -1); rs.eval()
    class W(torch.nn.Module):
        def __init__(s): super().__init__(); s.n = net; s.rs = rs
        def forward(s, phone, pitch, pitchf):
            lens = torch.tensor([T], dtype=torch.long); sid = torch.tensor([0], dtype=torch.long)
            o, _, _ = s.n.infer(phone, lens, pitch, pitchf, sid, skip_head=T-L, return_length=L)
            if s.rs is not None:
                nn_ = o.shape[-1]; u = s.rs(o.view(1, 1, -1)); off = (UP*24-1)//2
                o = u[..., off:off+nn_*UP:DN]
            return o.view(1, 1, -1)[..., :L*480]
    w = W().eval(); f = f'{OUT}/_tmp.onnx'
    phone = torch.randn(1, T, EMB); pitch = torch.randint(1, 255, (1, T)); pitchf = torch.rand(1, T)*300+100
    with torch.no_grad():
        torch.onnx.export(w, (phone, pitch, pitchf), f, input_names=['phone', 'pitch', 'pitchf'], output_names=['audio'], opset_version=17, dynamo=False, do_constant_folding=False)
    m = onnx.load(f); os.remove(f)
    # 候选：plain / wn 折叠 / 2D 转置 / emb_g 第 0 行
    cand = []
    for k, v in sd.items():
        a = v.numpy().astype(np.float32)
        if k.endswith('weight_v'): continue
        if k.endswith('weight_g'):
            b = k[:-2]; vv = sd[b+'_v'].numpy().astype(np.float64)
            nrm = np.sqrt((vv**2).reshape(vv.shape[0], -1).sum(1)).reshape((-1,)+(1,)*(vv.ndim-1))
            cand.append(dict(src=b, op='wn', val=(sd[k].numpy().astype(np.float64)*vv/nrm).astype(np.float32))); continue
        cand.append(dict(src=k, op='plain', val=a))
        if a.ndim == 2: cand.append(dict(src=k, op='T', val=a.T.copy()))
        if k == 'emb_g.weight': cand.append(dict(src=k, op='row0', val=a[0].copy()))
    by = {}
    for c in cand: by.setdefault(c['val'].size, []).append(c)
    recipe = []; keep = []
    from onnx import numpy_helper as nh
    for init in list(m.graph.initializer):
        if init.data_type != onnx.TensorProto.FLOAT: keep.append(init); continue
        x = nh.to_array(init).astype(np.float32).ravel()
        hit = [c for c in by.get(x.size, []) if np.allclose(c['val'].ravel(), x, atol=1e-5, rtol=1e-4)]
        if x.size > 4 and hit:
            c = hit[0]; recipe.append(dict(name=init.name, src=c['src'], op=c['op'], dims=list(init.dims)))
        elif x.size <= 4 and hit and any(c['op'] != 'plain' or True for c in hit) and init.name.startswith(('n.', 'onnx::MatMul')):
            c = hit[0]; recipe.append(dict(name=init.name, src=c['src'], op=c['op'], dims=list(init.dims)))
        else:
            keep.append(init)
    del m.graph.initializer[:]; m.graph.initializer.extend(keep)
    used = {r['src'] for r in recipe}
    return m, recipe, cfg, EMB, sd, used
if __name__ == '__main__':
    combos = [('v2', 48000), ('v2', 40000), ('v2', 32000), ('v1', 48000), ('v1', 40000), ('v1', 32000)]
    index = []
    for ver, sr in combos:
        for L in (35, 55):
            m, recipe, cfg, EMB, sd, used = build(ver, sr, L)
            unused = [k for k in sd if not k.endswith(('weight_v',)) and (k[:-2] if k.endswith('weight_g') else k) not in used]
            meta = dict(format='micmask-stream', fmt=1, version=ver, embChannels=EMB, hubertOutput='feat' if EMB == 768 else 'feat9',
                        srModel=sr, srOut=48000, f0=1, frames=T, outFrames=L, blockSec=L/100, tier='std' if L == 35 else 'b50', fromPth=1)
            del m.metadata_props[:]
            m.metadata_props.append(onnx.StringStringEntryProto(key='micmask', value=json.dumps(meta)))
            nm = f'tpl_{ver}_{sr//1000}k_L{L}'
            onnx.save(m, f'{OUT}/{nm}.onnx')
            json.dump(dict(version=ver, sr=sr, L=L, upsample=cfg[12], recipe=recipe), open(f'{OUT}/{nm}.json', 'w'))
            print(nm, 'recipe', len(recipe), 'tpl', os.path.getsize(f'{OUT}/{nm}.onnx')//1024, 'KB', 'unused', unused[:8], len(unused))

# ---- v2：不做常量折叠 + 生成 QDQ 模板 ----
def quant_template(ver, sr, L, outdir):
    from onnxruntime.quantization import quantize_static, CalibrationDataReader, QuantFormat, QuantType
    from onnxruntime.quantization.shape_inference import quant_pre_process
    from onnx import numpy_helper as nh
    m, recipe, cfg, EMB, sd, used = build(ver, sr, L)
    # 先把权重按配方补回去，得到随机权重的完整 fp32 模型，再做与 convert_rvc.py 相同的预处理（但跳过图优化，避免把权重折进常量）
    import copy
    full = copy.deepcopy(m)
    cand = {}
    for k, v in sd.items():
        cand[k] = v.numpy().astype(np.float32)
    def calc(r):
        if r['op'] == 'wn':
            g = sd[r['src']+'_g'].numpy().astype(np.float64); vv = sd[r['src']+'_v'].numpy().astype(np.float64)
            n = np.sqrt((vv**2).reshape(vv.shape[0], -1).sum(1)).reshape((-1,)+(1,)*(vv.ndim-1)); a = g*vv/n
        else:
            a = cand[r['src']]
            if r['op'] == 'T': a = a.T
            if r['op'] == 'row0': a = a[0]
        return np.ascontiguousarray(a, dtype=np.float32).reshape(r['dims'])
    for r in recipe: full.graph.initializer.append(nh.from_array(calc(r), r['name']))
    f32 = f'{outdir}/_f32.onnx'; onnx.save(full, f32)
    quant_pre_process(f32, f32+'.pre', skip_optimization=True, skip_symbolic_shape=True)
    pre = onnx.load(f32+'.pre')
    z = np.load('/workspace/work/micmask-repo/tools/calib.npz')
    ph = z['phone9'] if EMB == 256 else z['phone']
    data = [dict(phone=ph[i:i+1].astype(np.float32), pitch=z['pitch'][i:i+1], pitchf=z['pitchf'][i:i+1]) for i in range(8)]
    class R(CalibrationDataReader):
        def __init__(s): s.it = iter(data)
        def get_next(s): return next(s.it, None)
    q = f'{outdir}/_q.onnx'
    quantize_static(f32+'.pre', q, R(), quant_format=QuantFormat.QDQ, op_types_to_quantize=['Conv'], per_channel=True,
                    activation_type=QuantType.QUInt8, weight_type=QuantType.QInt8, extra_options={'ActivationSymmetric': False})
    qm = onnx.load(q)
    return pre, qm, recipe, EMB
