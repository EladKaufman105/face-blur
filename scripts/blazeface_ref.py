"""Python reference for BlazeFace full-range (MediaPipe, Apache-2.0): the letterbox, anchors, box
decoding and weighted NMS as MediaPipe's calculators do them. Checked against MediaPipe's own
FaceDetector task (pip install mediapipe) on three clips: same faces, scores within 1e-6, boxes
within 2 px. The Android (BlazeFace.kt) and web (blazeface.ts) code are tested against this.

Needs: pip install ai-edge-litert numpy opencv-python-headless. Usage: see blazeface_fixture.py.
"""
import numpy as np, cv2
from ai_edge_litert.interpreter import Interpreter

S=192; STRIDE=4; N=(S//STRIDE)**2
def anchors():
    a=[]
    for y in range(S//STRIDE):
        for x in range(S//STRIDE):
            a.append(((x+0.5)/(S//STRIDE),(y+0.5)/(S//STRIDE)))
    return np.array(a,dtype=np.float32)   # w=h=1 (fixed_anchor_size)
ANCH=anchors()

def decode(reg, logits, thr):
    sc=1/(1+np.exp(-np.clip(logits[:,0],-100,100)))
    keep=np.where(sc>=thr)[0]
    out=[]
    for i in keep:
        r=reg[i]; ax,ay=ANCH[i]
        xc=r[0]/S+ax; yc=r[1]/S+ay; w=r[2]/S; h=r[3]/S
        kps=[(r[4+2*k]/S+ax, r[5+2*k]/S+ay) for k in range(6)]
        out.append((float(sc[i]),xc-w/2,yc-h/2,xc+w/2,yc+h/2,kps))
    return out

def iou(a,b):
    ix=max(0,min(a[3],b[3])-max(a[1],b[1])); iy=max(0,min(a[4],b[4])-max(a[2],b[2]))
    inter=ix*iy; u=(a[3]-a[1])*(a[4]-a[2])+(b[3]-b[1])*(b[4]-b[2])-inter
    return inter/u if u>0 else 0

def wnms(dets, thr=0.3):
    rem=sorted(dets,key=lambda d:-d[0]); out=[]
    while rem:
        top=rem[0]; cand=[]; rest=[]
        for d in rem:
            (cand if iou(d,top)>thr else rest).append(d)
        tot=sum(d[0] for d in cand)
        box=[sum(d[0]*d[k] for d in cand)/tot for k in (1,2,3,4)]
        out.append((top[0],*box,top[5]))
        if len(rest)==len(rem): break
        rem=rest
    return out

def prep(rgb, letterbox=True):
    h,w=rgb.shape[:2]
    if letterbox:
        s=S/max(h,w); nw,nh=round(w*s),round(h*s)
        s=S/max(h,w); ox=(S-w*s)/2; oy=(S-h*s)/2
        M=np.array([[s,0,ox],[0,s,oy]],np.float64)
        canvas=cv2.warpAffine(rgb,M,(S,S),flags=cv2.INTER_LINEAR,borderMode=cv2.BORDER_CONSTANT,borderValue=(0,0,0))
        pad=(ox/S,oy/S,ox/S,oy/S)
    else:
        canvas=cv2.resize(rgb,(S,S),interpolation=cv2.INTER_LINEAR); pad=(0,0,0,0)
    return ((canvas.astype(np.float32)-127.5)/127.5)[None], pad

class Ref:
    def __init__(self,path):
        self.it=Interpreter(model_path=path); self.it.allocate_tensors()
        self.i0=self.it.get_input_details()[0]
    def raw(self,x):
        self.it.set_tensor(self.i0['index'],x); self.it.invoke()
        r=[self.it.get_tensor(d['index'])[0] for d in self.it.get_output_details()]
        return [a for a in r if a.shape[1]==16][0],[a for a in r if a.shape[1]==1][0]
    def detect(self,rgb,thr=0.5,letterbox=True):
        h,w=rgb.shape[:2]
        x,pad=prep(rgb,letterbox)
        reg,lg=self.raw(x)
        dets=wnms(decode(reg,lg,thr))
        l,t,r,b=pad; sx=1-l-r; sy=1-t-b
        res=[]
        for d in dets:
            f=lambda v,o,s:(v-o)/s
            res.append((d[0],f(d[1],l,sx)*w,f(d[2],t,sy)*h,f(d[3],l,sx)*w,f(d[4],t,sy)*h))
        return res
