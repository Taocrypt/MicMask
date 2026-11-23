import onnxruntime as ort, numpy as np, wave, sys, time
w=wave.open('/workspace/work/rvcexp/jfk.wav'); x=(np.frombuffer(w.readframes(w.getnframes()),np.int16)/32768).astype(np.float32)
src=x[16000:16000+16320][None]
o=ort.SessionOptions(); o.graph_optimization_level=ort.GraphOptimizationLevel.ORT_ENABLE_BASIC; o.optimized_model_filepath='hubert_f32_static.onnx'; o.intra_op_num_threads=2
s=ort.InferenceSession('hubert_f32_static_raw.onnx',o)
a=s.run(None,{'source':src})[0]
r=ort.InferenceSession('/workspace/work/rvcexp/hubert_dyn8.onnx').run(None,{'source':src})[0]
print(a.shape,r.shape,'rel',np.abs(a-r).max()/np.abs(r).max(), 'cos',(a*r).sum()/np.linalg.norm(a)/np.linalg.norm(r))
