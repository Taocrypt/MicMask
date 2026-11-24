"""生成手机导入 .pth 的模板包：每个 (版本, 采样率, 档位) 一组
  <k>_f32.onnx   fp32 图（无权重，校准用）
  <k>_qdq.onnx   QDQ 图（无权重与量化参数）
  <k>.json       配方：recipe（fp32 权重怎么从 pth 算）+ plan（QDQ 每个初始化器怎么算）"""
import sys, json, os
OUT = sys.argv[1]
exec(open('/workspace/work/pthtpl/mk_tpl2.py').read().replace("if __name__ == '__main__':", "if False:"))
exec(open('/workspace/work/pthtpl/sim.py').read())
os.makedirs(OUT, exist_ok=True)
combos = [(v, s) for v in ('v2', 'v1') for s in (48000, 40000, 32000)]
only = sys.argv[2:]  # 可选：只生成某些
for ver, sr in combos:
    for L in (35, 55):
        k = f'{ver}_{sr//1000}k_L{L}'
        if only and k not in only: continue
        pre, qm, recipe, EMB = quant_template(ver, sr, L, OUT)
        rn = {r['name'] for r in recipe}
        f = onnx.ModelProto(); f.CopyFrom(pre)
        keep = [i for i in f.graph.initializer if i.name not in rn]
        del f.graph.initializer[:]; f.graph.initializer.extend(keep)
        t, plan = split_template(qm, recipe)
        meta = dict(format='micmask-stream', fmt=1, version=ver, embChannels=EMB, hubertOutput='feat' if EMB == 768 else 'feat9',
                    srModel=sr, srOut=48000, f0=1, frames=100, outFrames=L, blockSec=L/100, tier='std' if L == 35 else 'b50', fromPth=1)
        for mm in (f, t):
            del mm.metadata_props[:]; mm.metadata_props.append(onnx.StringStringEntryProto(key='micmask', value=json.dumps(meta)))
        onnx.save(f, f'{OUT}/{k}_f32.onnx'); onnx.save(t, f'{OUT}/{k}_qdq.onnx')
        for p in plan: p['r'] = p.get('r')
        json.dump(dict(version=ver, sr=sr, L=L, emb=EMB, recipe=recipe, plan=plan), open(f'{OUT}/{k}.json', 'w'))
        for x in ('_f32.onnx', '_f32.onnx.pre', '_q.onnx'):
            if os.path.exists(f'{OUT}/{x}'): os.remove(f'{OUT}/{x}')
        print(k, 'f32', os.path.getsize(f'{OUT}/{k}_f32.onnx')//1024, 'KB qdq', os.path.getsize(f'{OUT}/{k}_qdq.onnx')//1024, 'KB plan', len(plan), flush=True)
