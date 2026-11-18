# TEE and Keymaster - status notes

Offline analysis of saved dumps. No live TEE, RPMB or keymaster operation was
performed for this note, and no key material was used. The purpose is to record
what the platform does, what was found, and what is deliberately withheld.

Device: KD-55X9000H / BRAVIA VH1 / MT5895 / firmware `626241`.

Labels follow [evidence-policy.md](evidence-policy.md).

## Summary

| Area | Result | Label |
| --- | --- | --- |
| TEE image (`tzbp_a` / `tzbp_b`) | AVB-signed; payload encrypted at rest | PROVEN |
| Keymaster HAL | No cryptography in user space; forwards to the TEE | PROVEN |
| keystore2 storage | Only TEE-wrapped blobs and certificates | PROVEN |
| Secure-world entry paths | Two independent mechanisms, not one | PROVEN |
| DRM/attestation provisioning | Model-shared bundle, not device-unique | STRONG |
| DRM key containers | Symmetric container whose key ships in the normal world | STRONG |
| RPMB | Provisioned; contents not obtainable | PROVEN |
| Root cause of the 2026-11-03 stalls | OPEN |

## 1. The TEE image is opaque at rest

`tzbp_a` and `tzbp_b` are 6 MiB A/B slots carrying the secure-world firmware.
Both are AVB images: a footer at the end of the partition points to a `vbmeta`
block, which holds a hash, an RSA-2048 signature and one hash descriptor.

- The payload region measures at roughly 8 bits per byte across the whole span,
  with no ELF, compression or container magic anywhere in it. The payload is
  therefore encrypted at rest, not merely compressed. (PROVEN)
- Both slots are signed by the same key, and slot A is newer than slot B
  (rollback index), which is the normal result of an A/B update. (PROVEN)
- Slot A is byte-identical to the `tzbp` image shipped in the OTA package.
  (PROVEN)

Consequence: no trusted-application code, TA keys or TA identifiers can be
recovered from a partition dump. That route is closed.

## 2. Two separate secure-world entry paths

This is the part that was previously conflated, and it matters for diagnosis.

**Path 1 - Trusty IPC.** The keymaster HAL, the gatekeeper HAL and
`storageproxyd` open `/dev/trusty-ipc-dev0` and talk to named ports over the
Trusty IPC character device. The keymaster HAL is a shim: it links no crypto
library, imports only TIPC and keymaster message serialisation, and forwards
serialised requests to the TA. All key work happens on the other side. (PROVEN)

**Path 2 - MTAL direct SMC.** The DRM and attestation path is different. The
vendor library `libmtal.so` marshals a command, drives the MTAL kernel
interface, and the kernel ends in a secure-monitor call:

```
_TZ_CTL: ldr x0, =<vendor smc id> ; mov x1, cmd
         mov x2, phys_buf ; mov x3, len ; smc #0
```

So this path enters the secure world by SMC, not by TIPC. (PROVEN)

Two consequences worth stating plainly:

- The DRM key install reaches the TA named `com.android.trusty.kpm`, not the
  keymaster TA. Any explanation that blames the keymaster TA for a DRM install
  failure is wrong at the code level. (PROVEN)
- There is no timeout in the DRM path and there cannot be one. Code that has
  executed `smc #0` is no longer running in the normal world; it resumes when
  the secure side returns. A stalled secure side therefore looks like a hang,
  not an error. (PROVEN)

By contrast, Path 1 is an interruptible read; a wait there appears as a parked
thread rather than a hang. The two 2026-11-03 observations - a keymaster
generation wait and a provisioning wait - are on different mechanisms and should
not be treated as one fault.

## 3. Normal-world key storage

Every key row in the keystore2 database carries an opaque blob with the
keymaster blob magic, and every row reports the same single keymaster instance
identifier. Certificates are DER. The normal world never holds a plaintext
private key for a hardware-backed alias. (PROVEN)

The Keymaster secure-storage file under `/data/vendor/persist` is a cache with
a fixed zero prefix and an opaque body; it is not a key store readable by the
normal world, and it is not the RPMB medium. (PROVEN)

## 4. Factories ships the attestation bundle per model, not per device

The attestation material that provisioning installs is a static array inside
`/vendor/arib/bin/initSetupAttestation`, and the on-device copy is
byte-identical to it. The same binary carries equivalent arrays for the other
six platform families in this generation. The blob is the same on every unit of
a model family. (STRONG - one unit examined; the array comparison itself is
byte-exact.)

For all seven families the blob holds two typed blocks, an RSA-3072 identity
and an EC P-256 identity, each with a three-certificate chain terminating at
the same self-signed root.

The practical point for owners: this material is not device-unique, so it does
not identify a specific television.

## 5. What is withheld, and why

This note states findings, not recipes. The following are deliberately not
published, and are held locally only:

- key values of any kind, including the DRM container key;
- cipher parameters and the locations of that key material;
- decrypted container contents, and any private key recovered from them;
- wrapped keystore blobs, DRM blobs and the secure-storage file;
- the complete TEE port table and the exact command identifiers.

Reason: the container key is shared across seven model families, so publishing
it would expose attestation identities on units the owner does not control, and
the material sits squarely in DRM territory. Discovery-level statements are
sufficient to document the architecture; the operational detail is not.

The handling rules in [evidence-policy.md](evidence-policy.md) apply unchanged.

## 6. Open

- The precise cause of the 2026-11-03 secure-world waits. Two mechanisms are
  now distinguishable, but neither failure has a secure-world trace, and no
  such trace exists in the captured logs.
- Behaviour of any live verifier against these identities. Recoverability was
  examined; acceptance was not.
- Whether restore or rollback of provisioning state is ever accepted. DRM
  provisioning is bound to the RPMB counter, so file-level restores are
  expected to fail by design rather than silently succeed.

## 7. Limits

- The TV has been in a recovery loop since 2026-11-17, so nothing here is a
  claim about its present state.
- The payload encryption means the secure-world implementation itself was not
  examined; every statement about it in this repository is about the
  normal-world side of the boundary.
