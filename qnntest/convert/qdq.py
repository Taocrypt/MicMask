import sys, numpy as np, wave, onnx
from onnxruntime.quantization import QuantType, quantize, CalibrationDataReader, CalibrationMethod
from onnxruntime.quantization.execution_providers.qnn import get_qnn_qdq_config, qnn_preprocess_model
src, out, act = sys.argv[1], sys.argv[2], sys.argv[3]
feeds = np.load(sys.argv[4], allow_pickle=True)
class R(CalibrationDataReader):
    def __init__(s): s.it=iter(feeds)
    def get_next(s): return next(s.it, None)
pre = src.replace('.onnx','_pre.onnx')
changed = qnn_preprocess_model(src, pre, fuse_layernorm=True)
mi = pre if changed else src
print('preprocess changed', changed)
cfg = get_qnn_qdq_config(mi, R(), activation_type=QuantType.QUInt16 if act=='16' else QuantType.QUInt8, weight_type=QuantType.QUInt8, calibrate_method=CalibrationMethod.MinMax)
quantize(mi, out, cfg)
print('done', out)
