# Caustica source export

This repository contains the clean source tree corresponding to:

`caustica-0.1.0-relief-full-pathtraced-selfshadow-glint-capture-fix.jar`

Exported on 2026-07-26. Generated build outputs, extracted JAR contents, local
Gradle caches, compiled SPIR-V files, logs, profiling captures, and temporary
overlays are excluded.

## Included custom work

- LabPBR POM/parallax with configurable distance and smoothing
- path-traced relief self-shadowing for direct and continuation rays
- matched visible/self-shadow height-map LOD
- alpha-correct translucent ray traversal
- sun/scene-visibility-masked volumetric fog
- bloom, motion blur, procedural clouds, and voxel/PVS work present in the current tree
- enchantment-glint capture fix for armor and items

## Build prerequisites

- JDK 21
- Vulkan SDK tools: `glslangValidator` and `spirv-val`
- Slang compiler: `slangc`

Build with:

```powershell
.\gradlew.bat build
```

The build script compiles shader sources to SPIR-V; generated `.spv` files are
intentionally not committed in this export.
