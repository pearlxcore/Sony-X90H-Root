# Experimental module sources

Reference sources for the experimental modules and the MTAL ioctl client. No
compiled module is included. Build them against the exact Sony tree; several
depend on kernel/internal layouts and will not run on another build.

| Source | Status |
|---|---|
| `x90mjcg.c` + `hyb.sh` | Hybrid probe: calls `vDrvMJCGameMode(1)` -> Standard DI + MJC game ~121 ms. Reversible. `hyb.sh` is the device-side runner. |
| `x90pq.c` | PQ store instrument. **DO NOT LOAD.** The compiled module was recorded to hang at load. Kept for provenance. |
| `x90cust.c` | Userspace MTAL DRVCUST ioctl client (get/set). Experimental; validated for the tested ids. |

Build any module against the exact Sony `4.19.260-ab626241` tree with
`CONFIG_MODVERSIONS`; the CRCs are build-specific. No compiled module is
included. The root module (`x90mod.c`) is in the repository root under
`module/`.

## Safety

A kernel module can panic the TV. Build only against the exact firmware/tree and
do not load `x90pq.ko`. Do not unload the root module on a live daily driver:
the teardown path can panic in `cgroup_sk_free`.
