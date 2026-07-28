# Caustica prebuilt artifacts

## Voxy compatibility build

Install both files:

- `caustica-0.1.0-voxy-compat.jar`
  - Size: 40,080,699 bytes
  - SHA-256: `B8296989DEC331A942D8C97E3A88B8FA8710F1A183FD6E00A9E07BBFD192FED2`
- `voxy-0.2.18-beta-caustica.9-mc26.2.jar`
  - Size: 38,809,841 bytes
  - SHA-256: `5F5F5C3BC83CC186CE2A1DD71142C4A964FABE8E82D01BCDC2D2450757166CBE`

This Voxy edition is a CPU-side world/LOD provider for Caustica. It does not require Sodium and
does not start Voxy's standalone raster renderer. Caustica progressively converts Voxy LOD meshes
to BLAS/TLAS geometry so distant terrain participates in path-traced visibility, lighting and shadows.
The `.9` build ingests ordinary Fabric client chunks directly, safely hooks optional Chunky
pre-generation, and provides `/voxy import current` for existing singleplayer region files;
none of these paths depends on Sodium. It also fixes the exact Chunky receiver signature used
by the optional Mixin, preventing a crash while entering a world with Chunky installed. Fine
Voxy coverage now remains available beneath the vanilla RT window, while Caustica switches to
real chunks through an exact per-16x16x16-section readiness mask. This avoids both empty handoff
gaps and overlapping Voxy/vanilla triangles while either side is still streaming. LOD albedo now
comes from the active resource-pack sprites instead of Minecraft's coarse map colour, preserves
different colours for each block face, and applies the persisted Voxy biome to grass, foliage and
water. High-resolution packs use a bounded 64x64 average so first-time colour resolution remains
background work rather than a mesh-streaming spike. Streaming no longer restarts Caustica's whole
BLAS queue as progressively coarser Voxy rings appear. The expensive one-block ring follows the
actual vanilla render distance with a four-chunk hand-off margin, distant coarse rings are
prioritized for fast horizon coverage, material classification is cached once per voxel, and
opaque/transparent slices from one section share a bounded BLAS whenever possible.
Different flowing/waterlogged states of one fluid now cull their internal faces as a continuous
body, and hidden fluid faces against opaque lake beds and banks are omitted. Every Voxy water LOD
uses a ray-traced thin dielectric boundary rather than pretending sparse proxy voxels form a closed
volume, preventing repeated medium transitions, black intersecting sheets and grey distant lakes.
Real loaded-chunk water remains volumetric. States without a Minecraft MapColor use a stable
per-state fallback instead of literal mid-grey.

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
