"""Compare saved completion timing; no device access or inferred hardware PTS."""
import argparse
import json
import re
import statistics
from pathlib import Path
from x90cap import read_capture


def analyze(path, timing=None):
    capture=read_capture(path)
    frames=capture['frames']
    gaps=[(b[0]-a[0])/1e6 for a,b in zip(frames,frames[1:])]
    if not gaps:raise ValueError('Need at least two captured frames')
    base=statistics.median(gaps)
    # The lowest cadence cluster is the baseline; median can become2x if
    # most frames miss a cycle,so choose median of the fastest half.
    base=statistics.median(sorted(gaps)[:max(1,len(gaps)//2)])
    buckets={}
    for gap in gaps:
        multiple=max(1,round(gap/base));key=str(multiple)
        buckets[key]=buckets.get(key,0)+1
    ring={}
    for index,gap in enumerate(gaps):
        key=str(frames[index][2]);ring.setdefault(key,[]).append(gap)
    report=dict(file=Path(path).name,frames=len(frames),width=capture['width'],height=capture['height'],
                measured_completion_rate_hz=len(gaps)*1e9/(frames[-1][0]-frames[0][0]),
                baseline_interval_ms=base,interval_multiple_counts=buckets,
                min_interval_ms=min(gaps),max_interval_ms=max(gaps),
                approximate_missing_baseline_intervals=sum(max(0,round(t/base)-1) for t in gaps),
                ring_before_gap={key:dict(count=len(values),median_ms=statistics.median(values),
                                          over25ms=sum(g>25 for g in values)) for key,values in ring.items()},
                caveat='Callback delivery cadence only; interval multiples are descriptive, not proven dropped game frames.')
    if timing:
        text=Path(timing).read_text()
        rows=[tuple(map(int,m)) for m in re.findall(
            r'TIMING frame=(\d+) callback_ns=(\d+) trigger_begin_ns=(\d+) trigger_end_ns=(\d+)',text)]
        if len(rows)!=len(frames):raise ValueError('Timing log frame count mismatch')
        groups={'short':[],'long':[]}
        for i,callback,begin,end in rows[:-1]:
            if i>=len(gaps) or callback!=frames[i][0] or begin<callback or end<begin:
                raise ValueError('Timing log does not match capture')
            groups['long' if gaps[i]>25 else 'short'].append((begin-callback)/1000)
        report['trigger_delay_before_gap_us']={key:dict(count=len(values),
            median=statistics.median(values),maximum=max(values)) for key,values in groups.items() if values}
    return report


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('captures',type=Path,nargs='+')
    parser.add_argument('--timing-log',type=Path,help='Only valid with one capture')
    parser.add_argument('--out',type=Path,required=True)
    args=parser.parse_args()
    if args.out.exists():raise ValueError('Refusing overwrite')
    if args.timing_log and len(args.captures)!=1:raise ValueError('Timing log requires one capture')
    reports=[analyze(p,args.timing_log) for p in args.captures]
    args.out.write_text(json.dumps(reports,indent=2)+'\n')
    print(json.dumps(reports,indent=2))


if __name__=='__main__':main()
