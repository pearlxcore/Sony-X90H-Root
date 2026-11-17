# Split-screen A/B

`eng_split.sh` runs a live split comparison: Sony's SIDEBAND video on the left
through a transparent overlay, the custom engine on the right, and a divider at
the centre. It is self-restoring and PQ-verified.

## Files

- `eng_split.sh` - live runner (JOPT/ENGDEX/DISH/FUSH/UPSH overrides).

- `engine/X90FieldEngine.java` - the maintained engine source.
- `shaders/fu-h.frag`, `shaders/fu-v.frag` - runtime tone/zoom fastup.
- `shaders/field-di-vt.frag` - motion-gated deinterlace (di=3).

## Example

```sh
JOPT="-Dx90.fastup=1 -Dx90.split=0.5" nohup sh /data/local/tests/eng_split.sh TAG 60
```

The runner expects the engine dex/bundle and the device harness at the paths
used in the research. The full Android build harness and engine APK are not
included in this repository; the source is here for reference.

## What it established

- Sony uses motion-compensated deinterlace plus X1 Reality Creation in its
  silicon/firmware; the Mali GPU engine cannot match the fine texture.
- The engine matches Sony's 3.5% overscan (zoom height ratio 0.9995) and tone
  within 2-3%, but its callback-to-panel latency (~41 ms) already exceeds Sony
  Game (~37 ms).
- The engine is experimental and is not the product. The product direction is
  X90H Picture Control in `../../picture-control/`.

## Safety

The split runner creates an engine Surface, changes the Sony route and restores
both. Do not leave it running, and do not kill the root keeper. Use it only on
the exact firmware and a rooted TV.
