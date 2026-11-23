import onnx, numpy as np
from onnx import numpy_helper as nh, helper as H
m=onnx.load('/workspace/work/rvcexp/hubert_dyn8.onnx')
g=m.graph
init={i.name:i for i in g.initializer}
prod={o:n for n in g.node for o in n.output}
cons={}
for n in g.node:
    for i in n.input: cons.setdefault(i,[]).append(n)
def const(name):
    if name in init: return nh.to_array(init[name])
    n=prod.get(name)
    if n is not None and n.op_type=='Constant': return nh.to_array(n.attribute[0].t)
    return None
rm=set(); new=[]; cnt=0
for n in list(g.node):
    if n.op_type!='MatMulInteger': continue
    dql=prod[n.input[0]]; assert dql.op_type=='DynamicQuantizeLinear'
    X=dql.input[0]
    Wq=const(n.input[1]).astype(np.float32); wzp=const(n.input[3]).astype(np.float32) if len(n.input)>3 and n.input[3] else 0.0
    cast=cons[n.output[0]][0]; assert cast.op_type=='Cast'
    mul=cons[cast.output[0]][0]; assert mul.op_type=='Mul'
    sc_name=[i for i in mul.input if i!=cast.output[0]][0]
    smul=prod[sc_name]; assert smul.op_type=='Mul'
    ws=[const(i) for i in smul.input if i!=dql.output[1]][0]
    Wf=((Wq-wzp)*ws).astype(np.float32)
    wn=n.input[1]+'_f32'; g.initializer.append(nh.from_array(Wf,wn))
    new.append(H.make_node('MatMul',[X,wn],[mul.output[0]],name=n.name+'_f32'))
    rm.update([id(n),id(cast),id(mul),id(smul)])
    if all(c.op_type in('MatMulInteger','Mul') for c in sum([cons.get(o,[]) for o in dql.output],[])): rm.add(id(dql))
    cnt+=1
keep=[n for n in g.node if id(n) not in rm]+new
del g.node[:]; g.node.extend(keep)
# drop orphan DQL / unused initializers
for _ in range(5):
    used=set(i for n in g.node for i in n.input)|set(o.name for o in g.output)
    keep=[n for n in g.node if any(o in used for o in n.output)]
    del g.node[:]; g.node.extend(keep)
used=set(i for n in g.node for i in n.input)
ini=[i for i in g.initializer if i.name in used]; del g.initializer[:]; g.initializer.extend(ini)
# topo sort
from collections import deque
avail=set(i.name for i in g.input)|set(i.name for i in g.initializer)|{''}
nodes=list(g.node); out=[]; pending=nodes
while pending:
    rest=[]
    for n in pending:
        if all(i in avail for i in n.input): out.append(n); avail.update(n.output)
        else: rest.append(n)
    assert len(rest)<len(pending), 'cycle'
    pending=rest
del g.node[:]; g.node.extend(out)
# static input
g.input[0].type.tensor_type.shape.dim[1].dim_value=16320
for o in g.output:
    for d in o.type.tensor_type.shape.dim:
        if d.dim_param: d.ClearField('dim_param')
del g.value_info[:]
print('replaced',cnt,'nodes',len(g.node))
onnx.save(m,'hubert_f32_static_raw.onnx',save_as_external_data=False)
