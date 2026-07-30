# Caustica prebuilt artifacts

## Voxy compatibility build

Install both files:

- `caustica-0.1.0-voxy-compat.jar`
  - Size: 40,213,375 bytes
  - SHA-256: `4062EB0B89D5FE8B19FF191428F930BDAE213848E65EAB1AF5413365EA746554`
- `voxy-0.2.18-beta-caustica.11-mc26.2.jar`
  - Size: 38,810,490 bytes
  - SHA-256: `1FC472DA6C3986D74E68EBF794B2D234BDF9CD9FD41E0E13BB4E860FF1EF516B`

This Voxy edition is a CPU-side world/LOD provider for Caustica. It does not require Sodium and
does not start Voxy's standalone raster renderer. Caustica progressively converts Voxy LOD meshes
to BLAS/TLAS geometry so distant terrain participates in path-traced visibility, lighting and shadows.
The Caustica build routes direct illumination from the analytic sun/moon and all resolved emissive
geometry through ReSTIR DI. The light list covers real terrain, Distant Horizons/Voxy proxies,
entities and block entities, including vanilla block emission, LabPBR emission maps, heuristic
emission textures and configured material overrides. The selected point is traced through the normal
closest-hit path for exact geometry, orientation and occlusion. Authored emissive texture energy and
chromaticity are integrated on the CPU instead of resampling a sparse binary mask at the endpoint:
this removes the rare 50x-250x samples that appeared as moving white/orange shards. Fresh candidates run at every
path vertex; validated temporal reservoirs and optional four-neighbour spatial reuse remain on the
stable primary receiver. Celestial and local-emissive candidates use independent domains, preventing
an occluded sun from suppressing indoor emitters or transferring its normalization into torch
fireflies. Materials that merely provide a PBR specular map but have no non-zero emission channel are
excluded from the bounded light list, leaving its capacity for actual torches, lava and authored
emissive texels. Video Settings expose the ReSTIR toggle, candidate count and spatial-reuse toggle.
The celestial-atlas descriptor now follows the expanded guide/reservoir layout at set 0 binding 13.
The previous shader still used the pre-ReSTIR binding 9 and therefore sampled the `restirA0` storage
image as the sun/moon atlas, feeding reservoir values back into path misses as extreme HDR radiance.
The corrected descriptor ABI removes the celestial-atlas/reservoir feedback source.
History is invalidated on geometry publication/rebase, world, resource-pack, resolution and
enable-state changes. Local-emitter proposals are restricted to the nearby 64-block light domain,
with a separate 512-triangle budget for Voxy/Distant Horizons proxies. Within that domain, triangle
selection uses a CPU-built CDF weighted by actual triangle area, compiled average emitted power and
inverse squared distance from the camera. The exact inverse proposal PDF is applied only when a fresh
candidate enters the reservoir; temporal/spatial reuse retains the selected emitter's physical power.
This corrects the previous reservoir-measure mismatch and makes nearby relevant emitters common
samples instead of rare samples carrying the energy of the complete 60,000-triangle list.
The final endpoint remains fully ray traced, but tiny bright torch quads and sparse emission masks
no longer produce rare, enormous samples that Ray Reconstruction spreads into white/orange patches
and structured stripes. Average emitted power drives the triangle CDF and cancels analytically
against the proposal PDF; a packed average chromaticity supplies colour after endpoint validation.
Spatial reuse no longer samples the same invariant four-pixel cross: each tap uses a decorrelated
disk pattern, and spatial history has stricter normal/depth rejection than temporal reprojection.
This removes the regular micro-grid and prevents bright reservoirs from leaking across silhouettes
or rasterization-triangle boundaries.
The raw radiance input now applies a five-frame stationary EMA before Ray Reconstruction. History is
accepted only for an almost motionless pixel whose previous ReSTIR surface agrees in normal and
camera depth; moving geometry, camera motion, disocclusions, sky, water, glass and invalidated
reservoir history bypass it immediately. This reduces crawling one-ray torch/GI noise without extra
rays, descriptors or VRAM and without cross-pixel history reads that could produce ghost trails.
The user-facing candidate count and spatial reuse controls remain available.
The `.11` build adds live Voxy controls to Caustica's Video Settings screen: enable/disable,
new-chunk ingestion, a stepped 32-512 chunk LOD distance, and a bounded rebuild button. Changes
are saved to Voxy's own config and rebuild the desired LOD set without re-entering the world.
It ingests ordinary Fabric client chunks directly, safely hooks optional Chunky
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
per-state fallback instead of literal mid-grey. Water radiance rays now apply the same exact
per-section hand-off mask as solid terrain, so a Voxy water proxy cannot remain over a published
real chunk. Proxy water uses the normal water shader without claiming a closed absorption medium.
Non-voxel cross/partial models such as tall grass, torches and panes are no longer converted into
full transparent cubes; nearby real chunks still render their actual models.

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
