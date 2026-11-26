# qnntest-8：从 pthkit 的 v2_40k L35/L55 模板按常量线性外推出 L35/L30/L25 流式合成器，填重复的小随机权重（只计时，不含任何音色），再做 QDQ 量化。
# 用法：先解压 app/src/main/assets/pthkit.zip 的 v2_40k_* 到 /tmp/kit，再 python3 tier_synth_gen.py 35 30 25；产物拷到 qnntest/src/main/assets/tier/synth_L{L}_qdq.onnx（不进仓库）
import onnx, numpy as np, sys
from onnx import numpy_helper as nh
K='/tmp/kit/v2_40k'
a=onnx.load(K+'_L35_f32.onnx'); b=onnx.load(K+'_L55_f32.onnx')
def consts(m): return {n.name:n for n in m.graph.node if n.op_type=='Constant'}
ca,cb=consts(a),consts(b)
diffs=[]
for k,na in ca.items():
    va=nh.to_array(na.attribute[0].t); vb=nh.to_array(cb[k].attribute[0].t)
    if va.shape!=vb.shape or not np.array_equal(va,vb): diffs.append((k,va,vb))
def patch(L):
    m=onnx.load(K+'_L35_f32.onnx'); c=consts(m)
    for k,va,vb in diffs:
        if va.shape==vb.shape:
            v=va+(vb-va)*(L-35)/20.0
            assert np.allclose(v,np.round(v)), k
            v=np.round(v).astype(va.dtype)
        else:
            u=np.unique(va); assert len(u)==1 and np.unique(vb).size==1 and u[0]==np.unique(vb)[0], k
            shp=[int(round(x+(y-x)*(L-35)/20)) for x,y in zip(va.shape,vb.shape)]
            v=np.full(shp,u[0],dtype=va.dtype)
        c[k].attribute[0].t.CopyFrom(nh.from_array(v,c[k].attribute[0].t.name))
    rng=np.random.default_rng(0)
    for l in open(K+'_L35.plan'):
        p=l.rstrip('\n').split('\t')
        if p[0]!='R': continue
        shp=[int(x) for x in p[4].split(',')]
        base=(rng.standard_normal(257)*0.02).astype(np.float32); w=np.resize(base,shp)
        m.graph.initializer.append(nh.from_array(w,p[1]))
    del m.graph.value_info[:]
    for x in m.metadata_props:
        if x.key=='micmask': x.value=x.value.replace('"outFrames": 35','"outFrames": %d'%L).replace('"blockSec": 0.35','"blockSec": %s'%(L/100))
    onnx.save(m,f'rand_L{L}_f32.onnx')
print(len(diffs),'diffs')
for L in map(int,sys.argv[1:]): patch(L); print('L',L)
import numpy as np, onnx, json
from onnxruntime.quantization import quantize_static, CalibrationDataReader, QuantFormat, QuantType
from onnxruntime.quantization.shape_inference import quant_pre_process
rng=np.random.default_rng(1)
class R(CalibrationDataReader):
    def __init__(s): s.it=iter([dict(phone=(rng.standard_normal((1,100,768))*0.3).astype(np.float32),pitch=rng.integers(1,255,(1,100)).astype(np.int64),pitchf=(rng.random((1,100))*300+100).astype(np.float32)) for _ in range(4)])
    def get_next(s): return next(s.it,None)
for L in (35,30,25):
    f=f'rand_L{L}_f32.onnx'
    quant_pre_process(f,f+'.pre',skip_symbolic_shape=True)
    quantize_static(f+'.pre',f'rand_L{L}_qdq.onnx',R(),quant_format=QuantFormat.QDQ,op_types_to_quantize=['Conv'],per_channel=True,activation_type=QuantType.QUInt8,weight_type=QuantType.QInt8,extra_options={'ActivationSymmetric':False})
    print(L, 'ok')
