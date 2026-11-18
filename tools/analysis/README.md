# Analysis helpers

The firmware-specific reverse-engineering helpers are **not** published. They
expect private firmware dumps, vendor modules and device images, so shipping
them would either be broken or would cross the public/private boundary in
[../../docs/evidence-policy.md](../../docs/evidence-policy.md).

One standalone helper is included:

- `mtkcfg.py` - parse `MtkTvConfig` get/set lines from an adb logcat dump and
  emit the observed keys/values as JSON.

```sh
python mtkcfg.py logcat.txt --json observed.json
```

The driver/PQ/boot analysis is described in the write-ups; the scripts stay
with the private evidence.
