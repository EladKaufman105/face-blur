"""Exports torchvision's person-capable segmentation nets (BSD-3) to ONNX at the app's two frame
shapes, checks OpenCV 4.10 loads and runs them, and times them."""
import torch, torchvision, onnx, numpy as np, cv2, time, os
from onnxsim import simplify
from onnxconverter_common import float16
os.makedirs('out/seg', exist_ok=True)
models = {
    'lraspp_mbv3': torchvision.models.segmentation.lraspp_mobilenet_v3_large(weights='COCO_WITH_VOC_LABELS_V1'),
    'deeplabv3_mbv3': torchvision.models.segmentation.deeplabv3_mobilenet_v3_large(weights='COCO_WITH_VOC_LABELS_V1', aux_loss=True),
}
class PersonProb(torch.nn.Module):
    """Normalizes RGB 0..1 input and returns the person probability (VOC class 15)."""
    def __init__(self, m):
        super().__init__(); self.m = m.eval()
        self.register_buffer('mean', torch.tensor([0.485, 0.456, 0.406]).view(1, 3, 1, 1))
        self.register_buffer('std', torch.tensor([0.229, 0.224, 0.225]).view(1, 3, 1, 1))
    def forward(self, x):
        y = self.m((x - self.mean) / self.std)['out']
        return torch.softmax(y, 1)[:, 15:16]
for name, m in models.items():
    net = PersonProb(m)
    for (h, w) in [(288, 512), (512, 288), (384, 384)]:
        path = f'out/seg/{name}_{w}x{h}.onnx'
        torch.onnx.export(net, torch.rand(1, 3, h, w), path, input_names=['input'], output_names=['person'], opset_version=13)
        mm, ok = simplify(onnx.load(path)); assert ok; onnx.save(mm, path)
        h16 = path.replace('.onnx', '_fp16.onnx')
        onnx.save(float16.convert_float_to_float16(mm, keep_io_types=True), h16)
        for p in (path, h16):
            try:
                cvnet = cv2.dnn.readNetFromONNX(p)
                x = np.random.rand(1, 3, h, w).astype(np.float32)
                cvnet.setInput(x); o = cvnet.forward()
                t = time.time()
                for _ in range(5): cvnet.setInput(x); o = cvnet.forward()
                ref = net(torch.from_numpy(x)).detach().numpy()
                print(f'{os.path.basename(p)}: {os.path.getsize(p)/1e6:.1f} MB, opencv ok {o.shape}, {(time.time()-t)/5*1000:.0f} ms, max diff vs torch {np.abs(o-ref).max():.4f}')
            except Exception as e:
                print(f'{os.path.basename(p)}: opencv FAILED {str(e)[:200]}')
