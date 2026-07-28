# Caustica prebuilt artifacts

## Voxy compatibility build

Install both files:

- `caustica-0.1.0-voxy-compat.jar`
  - Size: 40,075,690 bytes
  - SHA-256: `1301D1311308DBEFAE59EADFA7614AECB56EFA72DA11F61A0D9DBF23AC51349A`
- `voxy-0.2.18-beta-caustica.5-mc26.2.jar`
  - Size: 38,798,017 bytes
  - SHA-256: `794A74925F069C900A2594028F8325D5984E1CFFB7B8EE9A7D9DDE71866C22F2`

This Voxy edition is a CPU-side world/LOD provider for Caustica. It does not require Sodium and
does not start Voxy's standalone raster renderer. Caustica progressively converts Voxy LOD meshes
to BLAS/TLAS geometry so distant terrain participates in path-traced visibility, lighting and shadows.
The `.5` build ingests ordinary Fabric client chunks directly, safely hooks optional Chunky
pre-generation, and provides `/voxy import current` for existing singleplayer region files;
none of these paths depends on Sodium. It also fixes the exact Chunky receiver signature used
by the optional Mixin, preventing a crash while entering a world with Chunky installed. Fine
Voxy coverage now remains available beneath the vanilla RT window, while Caustica switches to
real chunks through an exact per-16x16x16-section readiness mask. This avoids both empty handoff
gaps and overlapping Voxy/vanilla triangles while either side is still streaming.

Remove any other Voxy JAR from the `mods` folder. Sodium is not required by this build.

## Distant Horizons compatibility build

`caustica-0.1.0-distant-horizons-compat.jar`

- Size: 40,065,400 bytes
- SHA-256: `A7431469F49D116453DCC7CA4C9CD514E9E20B7213167D38436ED6525D032F8B`
- Includes progressive Distant Horizons LOD capture, RT proxy geometry, materials, shadows, and refresh UI.

Previous stable build:

`caustica-0.1.0-relief-full-pathtraced-selfshadow-glint-capture-fix.jar`

- Size: 39,971,398 bytes
- SHA-256: `CD9BBC835FF4AC944CE8C2FCA66FD02236F1591370FDAE969DB8D658A64C82DF`

All Caustica artifacts include bundled Windows and Linux NGX/DLSS runtime libraries.
