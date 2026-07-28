# Caustica prebuilt artifacts

## Voxy compatibility build

Install both files:

- `caustica-0.1.0-voxy-compat.jar`
  - Size: 40,071,954 bytes
  - SHA-256: `400A82EE9D0D450A2940746E9BAC1D288DF9582EDBF194BCC46694D8E58362F7`
- `voxy-0.2.18-beta-caustica.4-mc26.2.jar`
  - Size: 38,798,049 bytes
  - SHA-256: `EDC29D9BBA637D3D4533D376395B862EE6E10B8BB0208C337E12F5ABFCEAB9BC`

This Voxy edition is a CPU-side world/LOD provider for Caustica. It does not require Sodium and
does not start Voxy's standalone raster renderer. Caustica progressively converts Voxy LOD meshes
to BLAS/TLAS geometry so distant terrain participates in path-traced visibility, lighting and shadows.
The `.4` build ingests ordinary Fabric client chunks directly, safely hooks optional Chunky
pre-generation, and provides `/voxy import current` for existing singleplayer region files;
none of these paths depends on Sodium. It also fixes the exact Chunky receiver signature used
by the optional Mixin, preventing a crash while entering a world with Chunky installed.

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
