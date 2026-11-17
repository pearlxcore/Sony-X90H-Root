"""Offline luma/row diagnostics. Descriptive measurements, NOT a tearing detector.

Consumes validated X90CAP2 source frames, excludes stride padding and never
rescales images. A horizontal edge, HUD, moving object, field artifact or
capture fault can all produce a row outlier. Final panel timing is unobserved.
"""
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np
from PIL import Image
from x90cap import read_capture


def measure(luma):
    a=np.asarray(luma,dtype=np.float32)
    if a.ndim!=3 or min(a.shape)<2 or not np.isfinite(a).all():
        raise ValueError('Need at least two finite frames of at least 2x2 pixels')
    if a.min()<0 or a.max()>255:
        raise ValueError('Luma outside byte range')
    # Each column of temporal_rows is one successive frame pair.
    temporal=np.abs(np.diff(a,axis=0))
    temporal_rows=temporal.mean(axis=2).T
    spatial_rows=np.abs(np.diff(a,axis=1)).mean(axis=2)
    baseline=np.median(spatial_rows,axis=0)
    excess=np.maximum(0,spatial_rows-baseline)
    flat_index=int(excess.argmax())
    frame,row=np.unravel_index(flat_index,excess.shape)
    # A vertical local-extremum proxy also responds to ordinary fine textures.
    if a.shape[1]>2:
        up=a[:,1:-1]-a[:,:-2];down=a[:,1:-1]-a[:,2:]
        extrema=np.where(up*down>0,np.minimum(np.abs(up),np.abs(down)),0)
        alternation=extrema.mean(axis=(1,2))
    else:alternation=np.zeros(a.shape[0],dtype=np.float32)
    pairs=temporal.mean(axis=(1,2))
    summary=dict(frames=int(a.shape[0]),width=int(a.shape[2]),height=int(a.shape[1]),
        pair_mae_median=float(np.median(pairs)),pair_mae_max=float(pairs.max()),
        largest_pair_1based=[int(pairs.argmax()+1),int(pairs.argmax()+2)],
        max_row_edge_excess=dict(frame_1based=int(frame+1),boundary_after_row_0based=int(row),
                                 excess_luma_levels=float(excess[frame,row])),
        vertical_extremum_proxy_median=float(np.median(alternation)),
        pair_mae=pairs.tolist(),vertical_extremum_proxy=alternation.tolist(),
        interpretation='Descriptive only; no tear/combing diagnosis or quality ranking',
        limits='Intermediate FSC tap, client callback timestamps, no panel scanout or field-parity proof. '+
               'Natural edges, animation, noise and capture faults can produce outliers.')
    return summary,temporal_rows,excess.T


def heatmap(values,path):
    # Fixed 0..16 luma-level scale allows comparison; values above16 saturate.
    level=np.clip(values/16,0,1)
    rgb=np.stack((level*255,level*level*255,np.zeros_like(level)),axis=2).astype('uint8')
    Image.fromarray(rgb).save(path)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('capture',type=Path)
    parser.add_argument('output',type=Path,help='New evidence directory, must not exist')
    args=parser.parse_args()
    capture=read_capture(args.capture)
    w,h,s=(capture[k] for k in ('width','height','stride'))
    luma=np.stack([np.frombuffer(f[3],dtype=np.uint8,count=s*h).reshape(h,s)[:,:w]
                   for f in capture['frames']])
    report,temporal,excess=measure(luma)
    report.update(source_file=args.capture.name,source_sha256=hashlib.sha256(args.capture.read_bytes()).hexdigest(),
                  heatmap_axes='x=frame/pair index, y=source row; fixed black-to-yellow 0..16 luma levels',
                  stride_padding_excluded=True)
    args.output.mkdir()
    (args.output/'report.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    heatmap(temporal,args.output/'temporal-row-mae.png')
    heatmap(excess,args.output/'row-edge-excess.png')
    print(json.dumps({k:v for k,v in report.items() if k not in ('pair_mae','vertical_extremum_proxy')},indent=2))


if __name__=='__main__':main()
