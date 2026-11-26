#!/usr/bin/env python3
"""qnntest-4：把 MicMask 流式合成器的声码器（dec）导出成 fp32 ONNX 图，并按 mobile-rvc 的方法改写成骁龙 HTP 能接受的形式。

只用于 qnntest 测试包（不进正式版）。输入是 MicMask 现有的 <音色>_stream_qdq.onnx（QDQ int8，T=100 档）。

步骤
  1. 去量化：权重的 DequantizeLinear 折成 fp32 初始化器，激活上的 Q→DQ 对直接删掉 → fp32 整图。
  2. 截取 dec：输入 z[1,192,L]（conv_pre 的输入）和 har[1,1,L*upp]（m_source 的谐波源），
     输出 audio（40k/32k 模型带 48k 重采样尾巴；也输出不带尾巴的版本）。形状全部固定。
  3. 改写（mobile-rvc 的做法，见下面 rewrite_* 函数）：
     a. 谐波源上的 noise_convs（单输入通道卷积）：
        - kernel=1：逐通道乘加 har*w + b（Mul + Add）；
        - kernel=2s、stride=s、pad=s/2：har 按 s 做多相分解（Reshape [1,S/s,s] → Transpose [1,s,S/s]），
          变成 s 个输入通道上的普通 3 抽头卷积（stride 1，pad 1），权重 W'[c,p,q+1] = W[c, q*s+p+s/2]（越界为 0）。
     b. 说话人条件：长度为 1 的 kernel-1 卷积改成 MatMul（MicMask 模型里 cond 已被折进 conv_pre 的 bias，
        这一条在现有模型上通常是空操作，脚本会报告改了几处）。
     c. （MicMask 独有，mobile-rvc 没有）40k/32k→48k 的重采样尾巴是 1 进 1 出的 ConvTranspose(stride=up)，
        同样是单输入通道卷积，改写成多相 MatMul：24 个错位 Slice 拼成 [K',N+K'-1]，乘 [up,K'] 的多相滤波器，
        再 Transpose/Reshape 交织，最后保留原来的 Slice（步长 dn）。
  4. host 上用 onnxruntime CPU 验证：改写前后同一输入，报 SNR 和最大绝对误差；可选再跑一次 fp16 版（host 近似）。

用法
  python dec_rewrite.py <模型_stream_qdq.onnx> <输出目录> [--tag 名字] [--calib calib_synth.npy] [--synth]
  --synth 另外导出改写后的整个合成器（随机算子换成常量，只用于测速/建会话测试）。

输出（放在 <输出目录>，不要提交到仓库：角色音色的衍生模型不公开）
  <tag>_dec_f32.onnx        截取的 dec，未改写（对照：HTP 预计会拒）
  <tag>_dec_rw.onnx         改写后的 dec（含 48k 尾巴）
  <tag>_dec_rw_notail.onnx  改写后的 dec（到 tanh 为止，不含尾巴）
  <tag>_synth_rw.onnx       （--synth）改写后的整合成器
  每个 .onnx 旁边一个 .onnx.inputs：真实人声得到的输入（按会话输入顺序的 fp32 原始字节），qnntest App 自动读取。
  <tag>_report.json         验证结果

改写方法移植自 nasuiyile/mobile-rvc（tools/rvc_mobile/synth_modules.py 的 _source_conv / _point_conv），MIT License：
  Copyright (c) 2026 nasuiyile
  Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
  documentation files (the "Software"), to deal in the Software without restriction, including without limitation
  the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
  permit persons to whom the Software is furnished to do so, subject to the following conditions:
  The above copyright notice and this permission notice shall be included in all copies or substantial portions of
  the Software.
  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED. （全文见 notices/mobile-rvc-MIT.txt）
"""
import argparse, json, os, sys, collections
import numpy as np
import onnx
from onnx import helper as H, numpy_helper as nh, TensorProto
import onnxruntime as ort


# ---------------------------------------------------------------- 工具
def attr(n, k, d=None):
    for a in n.attribute:
        if a.name == k:
            return H.get_attribute_value(a)
    return d


def const_value(g, name):
    for i in g.initializer:
        if i.name == name:
            return nh.to_array(i)
    for n in g.node:
        if n.op_type == 'Constant' and n.output[0] == name:
            return nh.to_array(attr(n, 'value'))
    return None


def dims(vi):
    return [d.dim_value if d.HasField('dim_value') else d.dim_param for d in vi.type.tensor_type.shape.dim]


# ---------------------------------------------------------------- 1. 去量化
def dequantize(m):
    g = m.graph
    init = {i.name: i for i in g.initializer}
    rename = {}
    keep = []
    new_inits = []
    nq = nd = 0
    prod = {o: n for n in g.node for o in n.output}
    for n in g.node:
        if n.op_type == 'DequantizeLinear' and n.input[0] in init:
            q = nh.to_array(init[n.input[0]]).astype(np.float64)
            s = nh.to_array(init[n.input[1]]).astype(np.float64)
            zp = nh.to_array(init[n.input[2]]).astype(np.float64) if len(n.input) > 2 and n.input[2] else np.zeros_like(s)
            if s.size > 1:
                ax = attr(n, 'axis', 1)
                if ax < 0: ax += q.ndim
                sh = [1] * q.ndim; sh[ax] = -1
                s = s.reshape(sh); zp = zp.reshape(sh)
            new_inits.append(nh.from_array(((q - zp) * s).astype(np.float32), n.output[0]))
            nd += 1
            continue
        if n.op_type == 'DequantizeLinear' and prod.get(n.input[0]) is not None and prod[n.input[0]].op_type == 'QuantizeLinear':
            rename[n.output[0]] = prod[n.input[0]].input[0]
            nq += 1
            continue
        keep.append(n)
    # 删掉没人用的 QuantizeLinear
    used = set(i for n in keep for i in n.input if n.op_type != 'QuantizeLinear')
    keep = [n for n in keep if not (n.op_type == 'QuantizeLinear' and n.output[0] not in used)]
    for n in keep:
        for i, x in enumerate(n.input):
            while x in rename: x = rename[x]
            n.input[i] = x
    for o in g.output:
        while o.name in rename: o.name = rename[o.name]
    del g.node[:]; g.node.extend(keep)
    g.initializer.extend(new_inits)
    left = [n.op_type for n in g.node if n.op_type in ('QuantizeLinear', 'DequantizeLinear')]
    if left:
        raise SystemExit(f'去量化后仍有 {len(left)} 个 Q/DQ 节点')
    prune(m)
    return dict(weight_dq=nd, act_qdq_pairs=nq)


def prune(m):
    g = m.graph
    need = set(o.name for o in g.output)
    live = []
    for n in reversed(list(g.node)):
        if any(o in need for o in n.output):
            live.append(n); need.update(i for i in n.input if i)
    live.reverse()
    del g.node[:]; g.node.extend(live)
    inits = [i for i in g.initializer if i.name in need]
    del g.initializer[:]; g.initializer.extend(inits)
    vis = [v for v in g.value_info if v.name in need]
    del g.value_info[:]; g.value_info.extend(vis)


def replace_random(m, seed=0):
    """整合成器：RandomNormalLike / RandomUniform 换成同形状常量（与 synth.py 相同的做法，只用于测速/建会话）。"""
    g = m.graph
    rnd = [n for n in g.node if n.op_type in ('RandomNormalLike', 'RandomUniform', 'RandomNormal', 'RandomUniformLike')]
    if not rnd: return 0
    probe = onnx.ModelProto(); probe.CopyFrom(m)
    for n in rnd: probe.graph.output.append(H.make_tensor_value_info(n.output[0], TensorProto.FLOAT, None))
    s = ort.InferenceSession(probe.SerializeToString(), providers=['CPUExecutionProvider'])
    feed = fake_synth_feed(s)
    outs = s.run([n.output[0] for n in rnd], feed)
    rs = np.random.RandomState(seed)
    for n, o in zip(rnd, outs):
        v = (rs.randn(*o.shape) if 'Normal' in n.op_type else np.zeros(o.shape)).astype(np.float32)
        c = H.make_node('Constant', [], [n.output[0]], value=nh.from_array(v, n.output[0] + '_c'), name=n.name + '_const')
        idx = list(g.node).index(n); g.node.remove(n); g.node.insert(idx, c)
    return len(rnd)


def fake_synth_feed(s):
    T = s.get_inputs()[0].shape[1]
    emb = s.get_inputs()[0].shape[2]
    rng = np.random.default_rng(0)
    return {'phone': rng.standard_normal((1, T, emb)).astype(np.float32),
            'pitch': np.full((1, T), 120, np.int64), 'pitchf': np.full((1, T), 200, np.float32)}


# ---------------------------------------------------------------- 2. 截取 dec
def find_dec_io(m):
    g = m.graph
    cp = [n for n in g.node if n.name == '/dec/conv_pre/Conv']
    if not cp: raise SystemExit('找不到 /dec/conv_pre/Conv')
    z = cp[0].input[0]
    noise = [n for n in g.node if n.op_type == 'Conv' and '/dec/noise_convs.' in n.name]
    hars = set(n.input[0] for n in noise)
    if len(hars) != 1: raise SystemExit(f'noise_convs 输入不唯一：{hars}')
    har = hars.pop()
    tanh = [n for n in g.node if n.name == '/dec/Tanh']
    return z, har, tanh[0].output[0], g.output[0].name


def extract(m, inputs, outputs, shapes):
    """手写截取（onnx.utils.Extractor 对大模型要写临时文件）：保留从 inputs 到 outputs 的节点。"""
    em = onnx.ModelProto(); em.CopyFrom(m)
    g = em.graph
    del g.input[:]; del g.output[:]
    for x in inputs: g.input.append(H.make_tensor_value_info(x, TensorProto.FLOAT, shapes[x]))
    for x in outputs: g.output.append(H.make_tensor_value_info(x, TensorProto.FLOAT, shapes.get(x)))
    stop = set(inputs)
    need = set(outputs); live = []
    for n in reversed(list(em.graph.node)):
        if any(o in need for o in n.output):
            live.append(n)
            need.update(i for i in n.input if i and i not in stop)
    live.reverse()
    del g.node[:]; g.node.extend(live)
    inits = [i for i in g.initializer if i.name in need]
    del g.initializer[:]; g.initializer.extend(inits)
    del g.value_info[:]
    # 检查没有漏掉的外部输入
    have = set(inputs) | set(i.name for i in g.initializer) | set(o for n in g.node for o in n.output)
    miss = set(i for n in g.node for i in n.input if i and i not in have)
    if miss: raise SystemExit(f'截取后缺输入：{list(miss)[:5]}')
    return em


def infer_shapes(m):
    m2 = onnx.shape_inference.infer_shapes(m, data_prop=True)
    vi = {}
    for v in list(m2.graph.value_info) + list(m2.graph.input) + list(m2.graph.output):
        vi[v.name] = dims(v)
    return vi


# ---------------------------------------------------------------- 3. 改写
def rewrite_noise_convs(m, shapes):
    """单输入通道卷积（谐波源上的 noise_convs）→ 逐通道乘加 / 多相 3 抽头卷积。返回改写列表。"""
    g = m.graph
    init = {i.name: i for i in g.initializer}
    done = []
    nodes = list(g.node)
    new_nodes = []
    phase_cache = {}  # (har, s) -> 多相张量名
    for n in nodes:
        if n.op_type != 'Conv' or n.input[1] not in init:
            new_nodes.append(n); continue
        w = nh.to_array(init[n.input[1]])
        if w.ndim != 3 or w.shape[1] != 1 or attr(n, 'group', 1) != 1:
            new_nodes.append(n); continue
        cout, _, k = w.shape
        b = nh.to_array(init[n.input[2]]) if len(n.input) > 2 and n.input[2] else np.zeros(cout, np.float32)
        s = attr(n, 'strides', [1])[0]; pads = attr(n, 'pads', [0, 0]); dil = attr(n, 'dilations', [1])[0]
        x = n.input[0]; y = n.output[0]; base = n.name.replace('/', '_').strip('_')
        if dil != 1: raise SystemExit(f'{n.name}: dilation={dil} 不支持')
        if k == 1:
            assert s == 1 and list(pads) == [0, 0], n.name
            wn, bn = base + '_gain', base + '_offset'
            g.initializer.extend([nh.from_array(w.reshape(1, cout, 1).astype(np.float32), wn),
                                  nh.from_array(b.reshape(1, cout, 1).astype(np.float32), bn)])
            t = base + '_mul'
            new_nodes.append(H.make_node('Mul', [x, wn], [t], name=n.name + '_rw_mul'))
            new_nodes.append(H.make_node('Add', [t, bn], [y], name=n.name + '_rw_add'))
            done.append(dict(node=n.name, kind='k1→Mul+Add', cout=int(cout)))
            continue
        if not (k == 2 * s and s % 2 == 0 and list(pads) == [s // 2, s // 2]):
            raise SystemExit(f'{n.name}: k={k} s={s} pads={pads} 不是 noise_conv 的形状')
        S = shapes[x][-1]
        assert S % s == 0, (n.name, S, s)
        key = (x, s)
        if key not in phase_cache:
            r = f'{x}_poly{s}'
            shp = r + '_shape'
            g.initializer.append(nh.from_array(np.array([1, S // s, s], np.int64), shp))
            new_nodes.append(H.make_node('Reshape', [x, shp], [r + '_r'], name=f'/dec/har_poly{s}/Reshape'))
            new_nodes.append(H.make_node('Transpose', [r + '_r'], [r], perm=[0, 2, 1], name=f'/dec/har_poly{s}/Transpose'))
            phase_cache[key] = r
        # wp[c, p, j] = w[c, (j)*s + p] of w padded by s/2 on both sides；j=0..2 对应 q=-1..1
        wpad = np.pad(w[:, 0, :], ((0, 0), (s // 2, s // 2)))            # [cout, 3s]
        wp = wpad.reshape(cout, 3, s).transpose(0, 2, 1).copy()          # [cout, s, 3]
        wn, bn = base + '_poly_w', base + '_poly_b'
        g.initializer.extend([nh.from_array(wp.astype(np.float32), wn), nh.from_array(b.astype(np.float32), bn)])
        new_nodes.append(H.make_node('Conv', [phase_cache[key], wn, bn], [y], name=n.name + '_rw_poly',
                                     kernel_shape=[3], strides=[1], pads=[1, 1], dilations=[1], group=1))
        done.append(dict(node=n.name, kind=f'k{k}/s{s}→多相 {s}ch×3 抽头 Conv', cout=int(cout)))
    del g.node[:]; g.node.extend(new_nodes)
    return done


def rewrite_point_convs(m, shapes):
    """长度 1 的 kernel-1 卷积（说话人条件）→ MatMul + Add。"""
    g = m.graph
    init = {i.name: i for i in g.initializer}
    done = []; new_nodes = []
    for n in g.node:
        if n.op_type == 'Conv' and n.input[1] in init and shapes.get(n.input[0], [None])[-1] == 1:
            w = nh.to_array(init[n.input[1]])
            if w.shape[2] == 1 and attr(n, 'group', 1) == 1:
                cout, cin, _ = w.shape
                b = nh.to_array(init[n.input[2]]) if len(n.input) > 2 and n.input[2] else np.zeros(cout, np.float32)
                base = n.name.replace('/', '_').strip('_')
                g.initializer.extend([nh.from_array(w.reshape(cout, cin), base + '_mm_w'), nh.from_array(b.reshape(1, cout, 1), base + '_mm_b')])
                new_nodes.append(H.make_node('MatMul', [base + '_mm_w', n.input[0]], [base + '_mm'], name=n.name + '_rw_mm'))
                new_nodes.append(H.make_node('Add', [base + '_mm', base + '_mm_b'], [n.output[0]], name=n.name + '_rw_add'))
                done.append(dict(node=n.name, kind='len1 k1 Conv→MatMul', cin=int(cin), cout=int(cout)))
                continue
        new_nodes.append(n)
    del g.node[:]; g.node.extend(new_nodes)
    return done


def rewrite_resample_tail(m, shapes):
    """1 进 1 出的 ConvTranspose(stride=up, K)（48k 重采样）→ 多相 MatMul。
    y[j*up+p] = sum_q x[j-q] h[q*up+p]，q=0..K'-1。X[r, j] = xpad[j+r]（xpad 两侧各补 K'-1 个 0），
    Hp[p, r] = h[(K'-1-r)*up + p]，Y = Hp @ X → [up, N+K'-1]，转置后展平即 ConvTranspose 的输出（长度 (N+K'-1)*up）。"""
    g = m.graph
    init = {i.name: i for i in g.initializer}
    done = []; new_nodes = []
    for n in g.node:
        if n.op_type == 'ConvTranspose' and n.input[1] in init:
            h = nh.to_array(init[n.input[1]])
            if h.shape[:2] == (1, 1) and attr(n, 'group', 1) == 1 and list(attr(n, 'pads', [0, 0])) == [0, 0] \
                    and not attr(n, 'output_padding') and (len(n.input) < 3 or not n.input[2]):
                up = attr(n, 'strides')[0]; K = h.shape[2]; Kp = -(-K // up)
                hh = np.zeros(Kp * up, np.float32); hh[:K] = h[0, 0]
                Hp = np.stack([hh[(Kp - 1 - r) * up:(Kp - 1 - r) * up + up] for r in range(Kp)], 1)  # [up, Kp]
                x = n.input[0]; N = shapes[x][-1]; base = n.name.replace('/', '_').strip('_')
                L = N + Kp - 1
                zn = base + '_zeros'; g.initializer.append(nh.from_array(np.zeros((1, 1, Kp - 1), np.float32), zn))
                xp = base + '_xpad'
                new_nodes.append(H.make_node('Concat', [zn, x, zn], [xp], axis=2, name=n.name + '_rw_pad'))
                cols = []
                for r in range(Kp):
                    st, en, ax = base + f'_s{r}', base + f'_e{r}', base + '_ax'
                    g.initializer.extend([nh.from_array(np.array([r], np.int64), st), nh.from_array(np.array([r + L], np.int64), en)])
                    if r == 0: g.initializer.append(nh.from_array(np.array([2], np.int64), ax))
                    c = base + f'_col{r}'
                    new_nodes.append(H.make_node('Slice', [xp, st, en, ax], [c], name=n.name + f'_rw_slice{r}'))
                    cols.append(c)
                X = base + '_X'
                new_nodes.append(H.make_node('Concat', cols, [X], axis=1, name=n.name + '_rw_cat'))      # [1, Kp, L]
                hn = base + '_Hp'; g.initializer.append(nh.from_array(Hp[None].astype(np.float32), hn))  # [1, up, Kp]
                Y = base + '_Y'
                new_nodes.append(H.make_node('MatMul', [hn, X], [Y], name=n.name + '_rw_mm'))           # [1, up, L]
                Yt = base + '_Yt'
                new_nodes.append(H.make_node('Transpose', [Y], [Yt], perm=[0, 2, 1], name=n.name + '_rw_tr'))  # [1, L, up]
                shp = base + '_oshape'; g.initializer.append(nh.from_array(np.array([1, 1, L * up], np.int64), shp))
                new_nodes.append(H.make_node('Reshape', [Yt, shp], [n.output[0]], name=n.name + '_rw_flat'))
                done.append(dict(node=n.name, kind=f'ConvTranspose 1→1 up={up} K={K}→{Kp} 个 Slice + Concat + MatMul[{up}x{Kp}] + 交织',
                                 note='MicMask 独有（48k 重采样尾巴），mobile-rvc 无此结构'))
                continue
        new_nodes.append(n)
    del g.node[:]; g.node.extend(new_nodes)
    return done


def fix_reshape_constants(m, shapes):
    """Reshape 的 -1 换成具体值（QNN 只认静态形状，避免运行期推导）。"""
    g = m.graph; n_fix = 0
    for n in g.node:
        if n.op_type == 'Reshape':
            out = shapes.get(n.output[0])
            if out and all(isinstance(d, int) and d > 0 for d in out):
                nm = n.name.replace('/', '_').strip('_') + '_static_shape'
                g.initializer.append(nh.from_array(np.array(out, np.int64), nm))
                n.input[1] = nm; n_fix += 1
    prune(m)
    return n_fix


def finalize(m, path):
    m = onnx.shape_inference.infer_shapes(m)
    del m.metadata_props[:]
    m.producer_name = 'micmask-qnntest-dec_rewrite'
    onnx.checker.check_model(m)
    onnx.save(m, path)
    return m


# ---------------------------------------------------------------- 4. 验证
def snr(ref, x):
    ref = np.asarray(ref, np.float64).reshape(-1); x = np.asarray(x, np.float64).reshape(-1)
    n = min(len(ref), len(x)); ref, x = ref[:n], x[:n]
    e = np.sum((ref - x) ** 2)
    return float(10 * np.log10(np.sum(ref ** 2) / max(e, 1e-30))), float(np.max(np.abs(ref - x)))


def sess(m_or_path, threads=2):
    so = ort.SessionOptions(); so.intra_op_num_threads = threads
    src = m_or_path if isinstance(m_or_path, str) else m_or_path.SerializeToString()
    return ort.InferenceSession(src, so, providers=['CPUExecutionProvider'])


def op_hist(m):
    return dict(collections.Counter(n.op_type for n in m.graph.node).most_common())


def single_in_convs(m):
    init = {i.name: i for i in m.graph.initializer}
    r = []
    for n in m.graph.node:
        if n.op_type in ('Conv', 'ConvTranspose') and n.input[1] in init:
            w = nh.to_array(init[n.input[1]])
            cin = w.shape[1] if n.op_type == 'Conv' else w.shape[0]
            if cin == 1: r.append(n.name)
    return r


def write_inputs(path, s, feed):
    with open(path + '.inputs', 'wb') as f:
        for i in s.get_inputs():
            f.write(np.ascontiguousarray(feed[i.name], np.float32 if feed[i.name].dtype != np.int64 else np.int64).tobytes())


def to_fp16(m):
    try:
        from onnxconverter_common import float16
    except Exception:
        return None
    return float16.convert_float_to_float16(m, keep_io_types=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('model'); ap.add_argument('out')
    ap.add_argument('--tag'); ap.add_argument('--calib', default='/workspace/work/qnnconv/calib_synth.npy')
    ap.add_argument('--synth', action='store_true'); ap.add_argument('--n', type=int, default=4)
    A = ap.parse_args()
    os.makedirs(A.out, exist_ok=True)
    tag = A.tag or os.path.basename(A.model).split('_stream')[0]
    rep = dict(model=os.path.basename(A.model), tag=tag)

    q = onnx.load(A.model)
    f32 = onnx.ModelProto(); f32.CopyFrom(q)
    rep['dequant'] = dequantize(f32)
    z, har, tanh_out, audio = find_dec_io(f32)
    shapes = infer_shapes(f32)
    rep['dec_io'] = dict(z=[z, shapes[z]], har=[har, shapes[har]], tanh=[tanh_out, shapes.get(tanh_out)], audio=[audio, shapes.get(audio)])
    print('dec 输入', rep['dec_io'])

    # 真实输入：calib（jfk.wav 的 hubert 特征 + 合成 f0），跑 fp32 整图拿 z/har
    probe = onnx.ModelProto(); probe.CopyFrom(f32)
    for x in (z, har, tanh_out):
        if x != audio: probe.graph.output.append(H.make_tensor_value_info(x, TensorProto.FLOAT, None))
    sp = sess(probe)
    emb = sp.get_inputs()[0].shape[2]; T = sp.get_inputs()[0].shape[1]
    feeds = list(np.load(A.calib, allow_pickle=True))[:A.n] if os.path.exists(A.calib) else [fake_synth_feed(sp)]
    cases = []
    for fd in feeds:
        fd = {k: v for k, v in fd.items()}
        fd['phone'] = fd['phone'][:, :T, :emb].astype(np.float32)
        fd['pitch'] = fd['pitch'][:, :T]; fd['pitchf'] = fd['pitchf'][:, :T]
        outs = dict(zip([o.name for o in sp.get_outputs()], sp.run(None, fd)))
        # 注：整图含随机算子（flow 噪声、正弦源噪声），每次运行不同，所以只比 dec（同一组 z/har），不和 QDQ 原图整图比
        cases.append(dict(feed=fd, z=outs[z], har=outs[har], audio32=outs[audio]))

    # 2. 截取
    variants = {}
    dec = extract(f32, [z, har], [audio], shapes)
    variants['dec_f32'] = dec
    rw = onnx.ModelProto(); rw.CopyFrom(dec)
    sh_rw = infer_shapes(rw)
    rep['rewrites'] = rewrite_noise_convs(rw, sh_rw) + rewrite_point_convs(rw, sh_rw) + rewrite_resample_tail(rw, sh_rw)
    rep['reshape_static'] = fix_reshape_constants(rw, infer_shapes(rw))
    variants['dec_rw'] = rw
    if tanh_out != audio:
        nt = extract(rw, [z, har], [tanh_out], infer_shapes(rw))
        variants['dec_rw_notail'] = nt
    else:
        rep['note_notail'] = '模型本身无重采样尾巴（48k），dec_rw 即到 tanh 为止'
    synth_ref = None
    if A.synth:
        sy = onnx.ModelProto(); sy.CopyFrom(f32)
        rep['synth_random_const'] = replace_random(sy)
        synth_ref = sess(sy)  # 同一组常量噪声、未改写：用来验证整合成器改写前后等价
        ssh = infer_shapes(sy)
        rep['synth_rewrites'] = [d['node'] for d in rewrite_noise_convs(sy, ssh) + rewrite_point_convs(sy, ssh) + rewrite_resample_tail(sy, ssh)]
        fix_reshape_constants(sy, infer_shapes(sy))
        variants['synth_rw'] = sy

    # 保存 + 验证
    rep['variants'] = {}
    ref_s = sess(variants['dec_f32'])
    ref_nt = sess(extract(f32, [z, har], [tanh_out], shapes)) if tanh_out != audio else None
    for name, mm in variants.items():
        path = os.path.join(A.out, f'{tag}_{name}.onnx')
        mm = finalize(mm, path)
        s = sess(path)
        info = dict(file=path, size_mb=round(os.path.getsize(path) / 1e6, 1), ops=op_hist(mm), single_input_channel_convs=single_in_convs(mm),
                    inputs=[(i.name, i.shape) for i in s.get_inputs()], outputs=[(o.name, o.shape) for o in s.get_outputs()], cases=[])
        for k, c in enumerate(cases):
            if name == 'synth_rw':
                fd = c['feed']; y = s.run(None, fd)[0]
                info['cases'].append(dict(vs_synth_norewrite_same_noise=snr(synth_ref.run(None, fd)[0], y),
                                          vs_fp32_full_snr_maxabs=snr(c['audio32'], y), note='第二项：随机算子换成常量，与原图噪声不同，仅供参考'))
                if k == 0: write_inputs(path, s, fd)
                continue
            fd = {z: c['z'], har: c['har']}
            y = s.run(None, fd)[0]
            ref = (ref_nt if name == 'dec_rw_notail' else ref_s).run(None, fd)[0]
            row = dict(vs_dec_f32_snr_maxabs=snr(ref, y))
            if name != 'dec_rw_notail':
                row['vs_full_fp32_audio'] = snr(c['audio32'], y)
            info['cases'].append(row)
            if k == 0: write_inputs(path, s, fd)
        # host fp16 近似（onnxconverter-common；ORT CPU 的 fp16 内核，不等于 HTP，但能看出 fp16 是否溢出）
        if name != 'synth_rw':
            h = to_fp16(onnx.load(path))
            if h is not None:
                try:
                    s16 = sess(h)
                    info['host_fp16_vs_fp32'] = [snr(s.run(None, {z: c['z'], har: c['har']})[0], s16.run(None, {z: c['z'], har: c['har']})[0]) for c in cases]
                except Exception as e:
                    info['host_fp16_vs_fp32'] = f'失败：{str(e)[:200]}'
        rep['variants'][name] = info
        print(name, json.dumps({k: v for k, v in info.items() if k not in ('ops',)}, ensure_ascii=False))
    with open(os.path.join(A.out, f'{tag}_report.json'), 'w') as f:
        json.dump(rep, f, ensure_ascii=False, indent=1)
    print('报告', os.path.join(A.out, f'{tag}_report.json'))


if __name__ == '__main__':
    main()
