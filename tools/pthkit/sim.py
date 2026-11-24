import numpy as np, onnx, onnxruntime as ort
from onnx import numpy_helper as nh, TensorProto
SUFS = ['_quantized_scale','_quantized_zero_point','_quantized','_scale','_zero_point']
def split_template(qm, recipe):
    """把 QDQ 模型拆成：模板（去掉所有待算初始化器）+ 计划 plan"""
    rn = {r['name']: r for r in recipe}
    cons = {}
    for n in qm.graph.node:
        for i, x in enumerate(n.input): cons.setdefault(x, []).append((n, i))
    prod = {o: n for n in qm.graph.node for o in n.output}
    plan = []; keep = []; acts = set()
    for init in qm.graph.initializer:
        nm = init.name; base = None; suf = None
        for s in SUFS:
            if nm.endswith(s): base, suf = nm[:-len(s)], s; break
        if nm in rn: plan.append(dict(kind='f32', name=nm, r=rn[nm])); continue
        if base is None: keep.append(init); continue
        if base in rn:
            dq = [n for n, i in cons.get(base+'_quantized', []) if n.op_type == 'DequantizeLinear'][0]
            axis = next((a.i for a in dq.attribute if a.name == 'axis'), 1)
            if init.data_type == TensorProto.INT32 or suf.startswith('_quantized_'):   # bias 一族
                if suf != '_quantized': continue   # 统一由 bias 条目一次写三个
                conv = cons[dq.output[0]][0][0]
                xdq = prod[conv.input[0]]; wdq = prod[conv.input[1]]
                plan.append(dict(kind='bias', name=base, r=rn[base], act=xdq.input[1][:-len('_scale')], w=wdq.input[0][:-len('_quantized')], axis=axis))
            else:
                if suf != '_quantized': continue
                plan.append(dict(kind='w', name=base, r=rn[base], axis=axis))
        else:
            if suf == '_scale': acts.add(base)
            continue
    for a in sorted(acts): plan.append(dict(kind='act', name=a))
    t = onnx.ModelProto(); t.CopyFrom(qm); del t.graph.initializer[:]; t.graph.initializer.extend(keep)
    return t, plan
def qweight(w, axis):
    wm = np.moveaxis(w, axis, 0).reshape(w.shape[axis], -1)
    amax = np.maximum(np.abs(wm.min(1).clip(max=0)), np.abs(wm.max(1).clip(min=0)))
    sc = (2*amax/254.0).astype(np.float32); sc[sc == 0] = 1.0
    shp = [1]*w.ndim; shp[axis] = -1
    q = np.clip(np.round(w / sc.reshape(shp)), -127, 127).astype(np.int8)
    return q, sc
def actq(mn, mx):
    mn = min(mn, 0.0); mx = max(mx, 0.0)
    sc = np.float32((mx - mn) / 255.0) if mx > mn else np.float32(1.0)
    zp = np.uint8(np.clip(np.round(0 - mn / sc), 0, 255))
    return sc, zp
