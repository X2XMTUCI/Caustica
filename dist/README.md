# Caustica prebuilt artifacts

## Voxy compatibility build

Install both files:

- `caustica-0.1.0-voxy-compat.jar`
  - Size: 40,071,954 bytes
  - SHA-256: `400A82EE9D0D450A2940746E9BAC1D288DF9582EDBF194BCC46694D8E58362F7`
- `voxy-0.2.18-beta-caustica.1-mc26.2.jar`
  - Size: 38,792,121 bytes
  - SHA-256: `AA733727D13EB26C7FF3C09B5655338FA06E1486B5C9A29A12C0BA8E8F5AAB40`

This Voxy edition is a CPU-side world/LOD provider for Caustica. It does not require Sodium and
does not start Voxy's standalone raster renderer. Caustica progressively converts Voxy LOD meshes
to BLAS/TLAS geometry so distant terrain participates in path-traced visibility, lighting and shadows.

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
