#!/usr/bin/env python3
"""把 RVC v1/v2（带音高 f0）.pth 转成 MicMask 实时变声用的流式合成器（48 kHz 输出，QDQ int8 量化）。

用法：
  python convert_rvc.py 模型.pth <音色id> <显示名> [--rvc RVC-WebUI目录] [--out 仓库根目录]
输出：
  <out>/models/<音色id>/<显示名>_stream_qdq.onnx      标准档（0.35 s 块）
  <out>/models/<音色id>/<显示名>_stream_b50_qdq.onnx  省电档（0.55 s 块）
  <out>/models/<音色id>/info.json
依赖：torch、onnx、onnxruntime、numpy；RVC WebUI 源码（https://github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI）
校准数据：同目录 calib.npz（54 段真实人声的 hubert 特征与音高）。
"""
import sys, os, json, argparse
ap = argparse.ArgumentParser()
ap.add_argument('pth'); ap.add_argument('vid'); ap.add_argument('name')
ap.add_argument('--rvc', default='rvc'); ap.add_argument('--out', default='.')
ap.add_argument("--gender", default="other", choices=["male", "female", "anime", "other"]); ap.add_argument("--author", default='第三方（转换：社区）'); ap.add_argument('--license', default='第三方模型，使用前请确认授权')
A = ap.parse_args()
HERE = os.path.dirname(os.path.abspath(__file__))
import torch, math, numpy as np
sys.path.insert(0, A.rvc)
from infer.module.models import SynthesizerTrnMs768NSFsid, SynthesizerTrnMs256NSFsid
pth = A.pth; LS = [35, 55]; T = 100
cpt=torch.load(pth,map_location='cpu',weights_only=False)
assert cpt.get('f0',1)==1, '只支持带音高(f0)的模型'
ver=cpt.get('version','v1'); cfg=cpt['config']; cfg[-3]=cpt['weight']['emb_g.weight'].shape[0]
SR=cfg[-1]; hop=math.prod(cfg[12])
print('version',ver,'sr',SR,'hop',hop)
Net=SynthesizerTrnMs768NSFsid if ver=='v2' else SynthesizerTrnMs256NSFsid
EMB=768 if ver=='v2' else 256   # v1 用 hubert 第 9 层 + final_proj（hubert_dual8.onnx 的 feat9 输出）
net=Net(*cfg,is_half=False); del net.enc_q
print(net.load_state_dict(cpt['weight'],strict=False)); net.eval()
for m in net.modules():
    if hasattr(m,'remove_weight_norm'):
        try: m.remove_weight_norm()
        except Exception: pass
# 采样率统一到 48k：多相 FIR（上采 up 倍卷积 + 每 down 取一）
g=math.gcd(48000,SR); UP,DN=48000//g,SR//g
rs=None
if UP!=DN:
    taps=UP*24; n=np.arange(taps)-(taps-1)/2; fc=0.5/max(UP,DN)*0.92
    h=2*fc*np.sinc(2*fc*n)*np.kaiser(taps,8.0)*UP
    rs=torch.nn.ConvTranspose1d(1,1,taps,stride=UP,bias=False)
    rs.weight.data=torch.tensor(h,dtype=torch.float32).view(1,1,-1); rs.eval()
class W(torch.nn.Module):
    def __init__(s,n,L): super().__init__(); s.n=n; s.L=L; s.rs=rs
    def forward(s, phone, pitch, pitchf):
        lens=torch.tensor([T],dtype=torch.long); sid=torch.tensor([0],dtype=torch.long)
        o,_,_=s.n.infer(phone,lens,pitch,pitchf,sid,skip_head=T-s.L,return_length=s.L)
        if s.rs is not None:
            n=o.shape[-1]; u=s.rs(o.view(1,1,-1)); off=(UP*24-1)//2
            o=u[...,off:off+n*UP:DN]
        return o.view(1,1,-1)[..., :s.L*480]
from onnxruntime.quantization import quantize_static, CalibrationDataReader, QuantFormat, QuantType
from onnxruntime.quantization.shape_inference import quant_pre_process
_z=np.load(os.path.join(HERE,'calib.npz'))
_ph=_z['phone9'] if EMB==256 else _z['phone']
data=[dict(phone=_ph[i:i+1].astype(np.float32),pitch=_z['pitch'][i:i+1],pitchf=_z['pitchf'][i:i+1]) for i in range(len(_z['pitch']))]
od=os.path.join(A.out,'models',A.vid); os.makedirs(od,exist_ok=True); pre=os.path.join(od,'_tmp')
class R(CalibrationDataReader):
    def __init__(s): s.it=iter(data)
    def get_next(s): return next(s.it,None)
for L in LS:
    w=W(net,L).eval(); f=f'{pre}_L{L}_fp32.onnx'
    phone=torch.randn(1,T,EMB); pitch=torch.randint(1,255,(1,T)); pitchf=torch.rand(1,T)*300+100
    with torch.no_grad():
        print('L',L,'out',w(phone,pitch,pitchf).shape)
        torch.onnx.export(w,(phone,pitch,pitchf),f,input_names=['phone','pitch','pitchf'],output_names=['audio'],opset_version=17,dynamo=False)
    quant_pre_process(f,f+'.pre',skip_symbolic_shape=True)
    quantize_static(f+'.pre',f'{pre}_L{L}_qdq.onnx',R(),quant_format=QuantFormat.QDQ,op_types_to_quantize=['Conv'],
      per_channel=True,activation_type=QuantType.QUInt8,weight_type=QuantType.QInt8,extra_options={'ActivationSymmetric':False})
    import onnx
    q=onnx.load(f'{pre}_L{L}_qdq.onnx')
    meta=dict(format='micmask-stream', fmt=1, version=ver, embChannels=EMB, hubertOutput='feat' if EMB==768 else 'feat9',
              srModel=SR, srOut=48000, f0=1, frames=T, outFrames=L, blockSec=L/100, tier='std' if L==35 else 'b50')
    del q.metadata_props[:]
    q.metadata_props.append(onnx.StringStringEntryProto(key='micmask', value=json.dumps(meta)))
    onnx.save(q, os.path.join(od, A.name + ('_stream_qdq.onnx' if L == 35 else '_stream_b50_qdq.onnx')))
    os.remove(f'{pre}_L{L}_qdq.onnx')
    for x in (f, f + '.pre'):
        if os.path.exists(x): os.remove(x)
    print('done', L)
json.dump({"name": A.name, "author": A.author, "desc": f"RVC {ver} {SR//1000}k→48k 流式", "license": A.license, "gender": A.gender},
          open(os.path.join(od, 'info.json'), 'w', encoding='utf-8'), ensure_ascii=False)
print('输出目录', od)
