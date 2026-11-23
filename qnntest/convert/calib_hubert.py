import numpy as np, wave
w=wave.open('/workspace/work/rvcexp/jfk.wav'); x=(np.frombuffer(w.readframes(w.getnframes()),np.int16)/32768).astype(np.float32)
f=[{'source':x[i:i+16320][None].copy()} for i in range(0,len(x)-16320,16000)][:10]
np.save('calib_hubert.npy',np.array(f,dtype=object),allow_pickle=True); print(len(f))
