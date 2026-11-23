import onnx, numpy as np, onnxruntime as ort, wave
from onnx import helper as H, numpy_helper as nh
src='/workspace/work/rvcexp/z2/atri48_stream_fp32.onnx'
m=onnx.load(src); g=m.graph
rnd=[n for n in g.node if n.op_type in('RandomNormalLike','RandomUniform')]
# 取形状
m2=onnx.load(src)
for n in rnd: m2.graph.output.append(H.make_tensor_value_info(n.output[0],onnx.TensorProto.FLOAT,None))
onnx.save(m2,'/tmp/synth_probe.onnx')
s=ort.InferenceSession('/tmp/synth_probe.onnx')
T=100
feed={'phone':np.random.randn(1,T,768).astype(np.float32),'pitch':np.full((1,T),120,np.int64),'pitchf':np.full((1,T),200,np.float32)}
outs=s.run([n.output[0] for n in rnd],feed)
rs=np.random.RandomState(0)
for n,o in zip(rnd,outs):
    v=(rs.randn(*o.shape) if n.op_type=='RandomNormalLike' else np.zeros(o.shape)).astype(np.float32)
    print(n.op_type,o.shape)
    c=H.make_node('Constant',[],[n.output[0]],value=nh.from_array(v,n.output[0]+'_c'),name=n.name+'_const')
    idx=list(g.node).index(n); g.node.remove(n); g.node.insert(idx,c)
del m.metadata_props[:]
onnx.save(m,'synth_f32_norand_raw.onnx')
o=ort.SessionOptions(); o.graph_optimization_level=ort.GraphOptimizationLevel.ORT_ENABLE_BASIC; o.optimized_model_filepath='synth_f32_static.onnx'
ort.InferenceSession('synth_f32_norand_raw.onnx',o)
m=onnx.load('synth_f32_static.onnx'); del m.metadata_props[:]; onnx.save(m,'synth_f32_static.onnx')
import collections; print(collections.Counter(n.op_type for n in m.graph.node).most_common())
# 校准数据
hs=ort.InferenceSession('hubert_f32_static.onnx')
w=wave.open('/workspace/work/rvcexp/jfk.wav'); x=(np.frombuffer(w.readframes(w.getnframes()),np.int16)/32768).astype(np.float32)
mel=lambda f:1127*np.log(1+f/700)
feeds=[]
for k,i in enumerate(range(0,len(x)-16320,16000)):
    if k>=10: break
    f=hs.run(None,{'source':x[i:i+16320][None]})[0]
    ph=np.repeat(f,2,axis=1)[:,:T]
    f0=(150+60*np.sin(np.arange(T)/7+k)).astype(np.float32)
    p=np.clip(np.rint((mel(f0)-mel(50))*254/(mel(1100)-mel(50))+1),1,255).astype(np.int64)
    feeds.append({'phone':ph.astype(np.float32),'pitch':p[None],'pitchf':f0[None]})
np.save('calib_synth.npy',np.array(feeds,dtype=object),allow_pickle=True)
print('calib',len(feeds))
