# Neo Voxy · Multiversion

[简体中文](README.md)

Neo Voxy is maintained by **JohnSnow**. It extends [NHblock714/voxy](https://github.com/NHblock714/voxy) with multiversion maintenance, client optimizations, and optional mod integrations.

> [!IMPORTANT]
> If an update causes problems, delete the Neo Voxy configuration and the Voxy cache inside the affected world before retrying.
>
> **Build artifacts**: Build artifacts are intentionally removed. Fork this repository to download
> the automatically built artifacts from GitHub Actions, or build locally by following the tutorial.
>
> **Server data sharing**: Neo Voxy is fully compatible with **[voxy-server-side](https://www.curseforge.com/minecraft/mc-mods/voxy-server-side-forge-neoforge)**. Deploying it on
> the server is recommended for sharing LOD data.
>
> **Dependencies and compatibility layers**: Neo Voxy only requires **Sodium** on NeoForge or
> **Embeddium** on Forge as its renderer dependency. Sinytra Connector and **Forgified Fabric API**
> are not required and are not recommended, as their compatibility layers may conflict with native
> NeoForge/Forge injections.


## Supported editions

| Edition | Install side | Renderer | Java | Release file |
|---|---|---|---:|---|
| 1.21.1 NeoForge integrations | Client and server | Sodium 0.8 / Iris | 21 | `neo-voxy-0.4.6-mc1.21.1-neoforge-integrations.jar` |
| 1.21.1 NeoForge client | Client only | Sodium 0.8 / Iris | 21 | `neo-voxy-0.3.0-mc1.21.1-neoforge-client.jar` |
| 1.20.1 Forge client | Client only | Embeddium / Oculus | 17 | `neo-voxy-0.3.3-mc1.20.1-forge-client.jar` |
| 26.1.2 NeoForge client | Client only | Sodium 0.9.1 / Iris 1.11.2+ | 25 | `neo-voxy-0.3.3-mc26.1.2-neoforge-client.jar` |

Release JARs remove unused platform natives, duplicate module descriptors, and build intermediates. Runtime shaders, languages, models, and storage libraries are retained.

## Feature and compatibility comparison

| Feature, mod, or component | 1.21.1 integrations | 1.21.1 client | 1.20.1 client | 26.1.2 client | Notes |
|---|:---:|:---:|:---:|:---:|---|
| Terrain LODs, detail levels, persistent cache | ✅ | ✅ | ✅ | ✅ | Core distant-terrain support |
| Sodium / Embeddium settings integration | ✅ | ✅ | ✅ | ✅ | Neo Voxy settings entry |
| Iris / Oculus shader pipeline | ✅ | ✅ | ✅ | ✅ | Shaders are supported on 1.20.1; compatibility may vary by shader pack |
| Environmental/sky fog and fluid fixes | ✅ | ✅ | ✅ | ✅ | Includes required medium masks such as underwater fog |
| Circular LOD handoff | ✅ | ✅ | ❌ | ✅ | Not planned for 1.20.1; disable in other editions when the shader pack supplies its own LOD transition |
| Crossed ground-plant models | ✅ | ✅ | ✅ | ✅ | Lightweight centred crossed models |
| Leaf LOD modes | ✅ | ✅ | ✅ | ✅ | Fast, Balanced, and Quality modes |
| Extended chunk requests (single-player, max 48) | ✅ | ✅ | ✅ | ✅ | Disabled by default |
| LOD biome water-colour blending | ✅ | ✅ | ✅ | ✅ | Built with the model |
| LOD build-pressure control | ✅ | ✅ | ✅ | ✅ | Prioritise frame rate or catch-up speed |
| World curvature | ✅ | ✅ | ✅ | ✅ | Implemented in the GPU vertex stage |
| Distant beacon beams | ✅ | — | — | — | Unbounded smooth width curve, shader shadows, and seamless handoff |
| Distant players, vehicles, and animation | ✅ | — | — | — | Integrations-edition feature |
| Sodium / Iris | ✅ | ✅ | — | ✅ | 26.1.2 targets Sodium 0.9.1 only, with Iris 1.11.2+ |
| Embeddium / Oculus | — | — | ✅ | — | Embeddium is the renderer; Oculus provides shader support |
| Create | ✅ | — | — | — | Distant trains, tracks, contraptions, and kinetic components |
| Sable | ✅ | — | — | — | Distant physics objects and depth integration |
| Ecliptic Seasons | ✅ | — | — | — | Seasonal snow in distant terrain |
| Domum Ornamentum | ✅ | — | — | — | Full support: detailed dedicated models, materials, and persistent cache |
| FramedBlocks | ✅ | — | — | — | Primary/secondary camos, special shape data, and persistent distant materials |
| LittleTiles | 🧪 | — | — | — | Preliminary: persistent lightweight 1/8-block LOD meshes for static structures |
| PowerGrid | ✅ | — | — | — | Full support: persistent simplified catenary LOD meshes for hanging wires |
| Simulated / Create Aeronautics | 🧪 | — | — | — | Laser-pointer beams remain visible beyond block-entity range |

`✅` means supported, `🧪` means preliminary compatibility, and `—` means no dedicated feature or not applicable; it does not necessarily imply incompatibility with basic terrain LOD rendering. Optional integrations activate only when the corresponding mod is installed. Create, Sable, and seasonal compatibility originate from **NHblock**.

### Main options

- Ground plants and leaves: all four editions provide centered crossed-plant LODs plus Fast, Balanced, and Quality leaf modes. Balanced culls hidden internal faces while keeping stable asymmetric cutouts. Leaves bypass alpha fading and hand directly between vanilla and LOD models, preventing the disappear/reappear cycle.
- Extended chunk requests: based on the approach used by [FakeSight](https://github.com/MoePus/fakesight), asks for chunks beyond vanilla distance in single-player. It is disabled by default and capped at 48 chunks in all four editions. Expansion pauses while moving and resumes gradually when stationary. High values can still increase CPU, memory, world-generation, and save load substantially.
- Biome water-colour blending: all four editions smooth LOD water colours across biome borders while models are built. Results use a compact palette.
- LOD build pressure: adjusts per-frame node processing and model-baking budgets from maximum FPS to maximum catch-up speed.
- Circular LOD handoff: editions containing this feature use 3D camera distance and world-stable dithering. Water and leaves use dedicated non-alpha handoff paths. Disable it when a shader pack already implements its own LOD transition, such as Photon, to avoid duplicate transitions, noise, or shadow seams.
- Fog and effects: shader-free fog on 1.20.1 and 1.21.1 scales against the LOD radius while preserving required medium masks such as underwater fog. Distant LODs no longer show through Blindness or Darkness. Version 26.1.2 uses the newer native fog path.
- Model and fluid quality: per-face mip generation and exact per-pixel tint masks reduce grass-side and waterlogged-plant colour errors; nearest rounding, independent fluid boundaries, and biome-colour handling improve distant water and terrain.
- Experimental Lite LOD shading: the 1.21.1 integrations build uses paired Lite programs and atomically falls back if loading or compilation fails, the version is unsupported, or the transition is unsafe. Eclipse Shader 482 is supported by a built-in NeoVoxy patch and does not require shader-pack changes; Complementary Unbound r5.8.1 + Euphoria Patches 1.9.3 uses a separate overlay.
- Subdivision size: controls the screen-space threshold for finer LODs. Lower values improve detail at higher build and rendering cost.
- World curvature: all four editions curve only the LOD beyond vanilla distance in the GPU vertex stage; 0 disables it.
- Distant beacons: the integrations edition builds beams from cached columns, hands off only after the vanilla beam is ready, uses a gentle unbounded width curve at extreme range, and submits lightweight shadow geometry during Iris shadow passes.
- PowerGrid distant wires: the integrations edition stores hanging wires the player has seen and reconstructs simplified catenaries from their endpoints and cable length beyond entity tracking range. This integration is considered fully compatible and has an independent switch and distance limit.
- FramedBlocks: the integrations edition records primary and secondary camos, reinforcement, collapsible offsets, and other block-entity model data as persistent section variants. LOD baking restores the complete `ModelData` and uses the camo block as its tint source.
- Simulated distant lasers: the integrations edition captures each laser pointer's dynamic direction, length, colour, and redstone strength, then continues rendering it through a dedicated translucent stage beyond block-entity range. It has an independent switch and distance limit.
- Join message: shown whenever a server or single-player world is entered, enabled by default and removable from the Neo Voxy Sodium/Embeddium settings.

## Building

Build one edition on Windows:

```powershell
.\scripts\build.ps1 integrations-1.21.1
.\scripts\build.ps1 client-1.21.1
.\scripts\build.ps1 client-1.20.1
.\scripts\build.ps1 client-26.1.2
```

Build all editions:

```powershell
.\scripts\build-all.ps1
```

The scripts prefer `JAVA_HOME_17`, `JAVA_HOME_21`, and `JAVA_HOME_25`. Linux/macOS equivalents are available as `scripts/build.sh` and `scripts/build-all.sh`. Final artifacts are copied to `dist/`; GitHub Actions builds the four editions in parallel and publishes one `neo-voxy-multiversion` bundle.

## Development note

Some code was completed with AI assistance and will be audited and lightly edited by the maintainer
before release. Compared with writing everything by hand, this substantially improves development
efficiency. Source comments are intentionally concise.

## License

See the license file shipped with each edition. Bundled third-party libraries retain their respective licenses.
