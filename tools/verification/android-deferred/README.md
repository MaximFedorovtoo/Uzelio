# Android deferred signaling regression

Runs the actual `AutoDiscoveryManager` and `PairingManager` sources on local JDK 17.
Core signatures, encrypted discovery signals, expiry and replay checks are real.
Small Android environment fixtures and a deterministic ICE transport isolate the
admission race without requiring a device, Gradle, DNS or external connections.
Node creates a local TLS listener, but the fixture never connects to it.

```powershell
./tools/verification/android-deferred/run.ps1 -ToolRoot C:/Users/Public/uzel-build-workspace/.tools
```

`ToolRoot` must contain an existing `jdk-17*` and the cached JUnit/Hamcrest jars
under `gradle-home`. The harness downloads nothing and writes compiled classes
only under `.tools/android-deferred-regression`.

Covers an offer arriving while its sender's old pair is active, natural close
then one successful admission of that same decrypted signal, replay rejection,
expiry without pairing mutation, explicit cancellation retiring deferred IDs,
new signal IDs after cancellation, a single queued admission and cancellation
generation, stale admission cancellation preserving a newer pending pair, rate
deferral before encrypted replay consumption, and a full cycle leaving background
pairing idle while an incoming admission is queued.

Before the fix, the natural-close regression failed with expected 1 admission,
actual 0. This test does not validate Android native WebRTC or real device links;
run the separate device failover acceptance for those behaviors.
