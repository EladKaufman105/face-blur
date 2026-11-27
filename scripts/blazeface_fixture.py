"""Makes the test fixture of the Kotlin and web decoders (BlazeFaceFixture.kt's frames, web/tests/blazeface-fixture.json) from three test clips.
Usage: python3 scripts/blazeface_fixture.py blaze_face_full_range.tflite MEDIA_DIR [json] > frames.txt
(MEDIA_DIR has headpose.mp4, classroom.mp4 and portrait_rot90.mp4, see ci/fetch-test-media.sh)."""
import os, sys, numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import blazeface_ref as B
ref = B.Ref(sys.argv[1])
MEDIA = sys.argv[2].rstrip('/') + '/'
out=[]
for name,frame in (('headpose.mp4',30),('classroom.mp4',60),('portrait_rot90.mp4',40)):
    cap=cv2.VideoCapture(MEDIA+name); cap.set(1,frame); ok,f=cap.read()
    rgb=cv2.cvtColor(f,cv2.COLOR_BGR2RGB); h,w=rgb.shape[:2]
    x,pad=B.prep(rgb); reg,lg=ref.raw(x)
    sc=1/(1+np.exp(-lg[:,0]))
    keep=np.where(sc>=0.2)[0]
    def norm(thr):
        l,t,r,b=pad; sx=1-l-r; sy=1-t-b
        res=[]
        for d in B.wnms(B.decode(reg,lg,thr)):
            res.append((d[0],(d[1]-l)/sx,(d[2]-t)/sy,(d[3]-l)/sx,(d[4]-t)/sy))
        return res
    out.append(dict(name=name.split('.')[0],w=w,h=h,
       anchors=[(int(i),float(lg[i,0]),*[float(v) for v in reg[i,:4]]) for i in keep],
       e05=norm(0.5), e03=norm(0.3), n05=len(norm(0.5)), n03=len(norm(0.3))))
if len(sys.argv) > 3 and sys.argv[3] == 'json':
    import json
    print(json.dumps([dict(name=o['name'], w=o['w'], h=o['h'], anchors=[[i, l, a, b, c, d] for i, l, a, b, c, d in o['anchors']], at05=[list(map(float, d)) for d in o['e05']], at03=[list(map(float, d)) for d in o['e03']]) for o in out]))
    sys.exit(0)
def f(v): return repr(float(np.float32(v)))
lines=[]
for fr in out:
    a=';'.join(f"{i}:{f(l)}:{f(a)}:{f(b)}:{f(c)}:{f(d)}" for i,l,a,b,c,d in fr['anchors'])
    e5=';'.join(','.join(f(v) for v in d) for d in fr['e05']); e3=';'.join(','.join(f(v) for v in d) for d in fr['e03'])
    lines.append(f'        Frame("{fr["name"]}", {fr["w"]}, {fr["h"]},\n            "{a}",\n            "{e5}",\n            "{e3}"),')
print('\n'.join(lines))
