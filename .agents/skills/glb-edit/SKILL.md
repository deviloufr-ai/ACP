---
name: glb-edit
description: Inspect and edit the launcher's GLB 3D models (app/src/main/assets/car.glb) — dump structure, author rotation animations (spinning wheels, opening doors), and the Blender workflow for separating fused geometry into animatable parts. Use when working with car.glb, .glb/.gltf models, Filament ModelViewer, or 3D model animation in this project.
---

# GLB editing (car.glb)

Tooling for the launcher's 3D car model. The model is rendered on-device by
**Filament `ModelViewer`** in [`Car3DPanel.kt`](../../../app/src/main/java/com/openauto/dash/Car3DPanel.kt).
Editing is done here on the desktop with **glTF-Transform** (Node), and geometry
surgery (splitting fused meshes) is done in **Blender**.

## Setup (once)
```bash
cd .Codex/skills/glb-edit
npm install          # installs @gltf-transform/* into ./node_modules (gitignored)
```

## Inspect a model — always start here
```bash
node scripts/inspect.mjs ../../../app/src/main/assets/car.glb
```
Prints scenes, nodes (name / mesh / children / TRS), meshes (vert count + **local
bbox center & size**), materials, animations, textures. The bbox center tells you
whether a part's geometry is offset from its node origin (it usually is), which
determines the pivot you need for animation.

### What car.glb contains (as of 2026-09-20)
- `_C-pcss.obj (Loose Mesh)` — **the entire car body fused into one mesh** (81,973
  verts). Doors/windows/hood are NOT separate — they cannot be animated until split
  in Blender (see below).
- `G-Object`, `.001`, `.002`, `.003` — **the 4 wheels** (17,110 verts each; `.002`
  and `.003` are mirrored, `scale = -1`). Axle = local **X**; geometry centered at
  local `1.25, 3.80, -3.80` (needs a pivot to spin in place).
- `G-Object.004` — small prop (141 verts).

## Author a rotation animation
`scripts/animate-rotate.mjs` adds a rotation clip to chosen nodes. It samples
keyframes (auto-raising the count so each slerp step ≤ 90° — needed for full
spins) and, when a part's geometry is offset from its node origin, **wraps it in a
pivot child** so it rotates in place instead of swinging in an arc. Verified: rest
positions are preserved exactly (Δ = 0).

**Spinning wheels** (already applied to car.glb as clip `"WheelSpin"`):
```bash
node scripts/animate-rotate.mjs \
  --in  ../../../app/src/main/assets/car.glb \
  --out ../../../app/src/main/assets/car.glb \
  --nodes "G-Object,G-Object.001,G-Object.002,G-Object.003" \
  --axis x --degrees 360 --duration 4 --mode spin \
  --pivot center --flip-mirrored --name "WheelSpin"
```
`--flip-mirrored` negates the angle on the mirrored (negative-scale) wheels so both
sides visually spin the same way. Writing to the same path leaves a `.glb.bak`.

**Opening a door** (only works AFTER the door is a separate node — see Blender):
```bash
node scripts/animate-rotate.mjs \
  --in ... --out ... \
  --nodes "DoorFL" --axis y --degrees 65 --duration 1.2 \
  --mode pingpong --pivot origin --name "DoorFL"
```
Use `--pivot origin` when the door's node origin already sits on the hinge line
(set that up in Blender), or `--pivot x,y,z` to give the hinge point explicitly.
Car doors hinge about the **vertical (Y) axis** at their front edge.

Full flag list is in the header comment of `scripts/animate-rotate.mjs`.

## Verify
- Re-run `inspect.mjs` and check the `ANIMATIONS` section lists your clip + channels.
- Preview visually: `<model-viewer>` (autoplay) served over a local http server, or
  any glTF viewer. Confirm parts rotate **in place** (hub stationary), not swinging.

## Playing animations on-device
Filament plays nothing on its own. [`Car3DPanel.kt`](../../../app/src/main/java/com/openauto/dash/Car3DPanel.kt)'s
frame callback drives `modelViewer.animator` — it loops clip **0** on wall-clock
time. If you add multiple clips and want a specific one (or blending), edit that
callback. Only clip index 0 auto-plays today.

## Splitting fused geometry (doors/hood) — Blender
The body is one mesh, so a CLI can't isolate a door. Do it once in Blender:

1. **Import** `app/src/main/assets/car.glb` (File ▸ Import ▸ glTF 2.0).
2. Select the body object → **Edit Mode** (Tab). Deselect all (Alt+A).
3. **Select the door faces** (hover + `L` to grab linked islands, or box/lasso
   select the door panel). This step is inherently manual — there's no reliable way
   to auto-detect a door in a fused shell.
4. `P` ▸ **Selection** to separate the faces into a new object. Rename it `DoorFL`
   (front-left), `DoorFR`, `DoorRL`, `DoorRR`.
5. **Set the hinge as the origin**: place the 3D cursor on the hinge line (front
   vertical edge of the door), then Object ▸ Set Origin ▸ **Origin to 3D Cursor**.
   Now the node origin sits on the hinge and you can animate with `--pivot origin`.
6. **Export** File ▸ Export ▸ glTF 2.0 (`.glb`), +Y up, back over `car.glb`.
7. Re-run `inspect.mjs` to confirm `DoorFL`… appear as their own nodes, then run
   `animate-rotate.mjs` (door example above).

`blender/split_doors.py` has helper functions (run from Blender's Text Editor /
`--python`) for the mechanical steps: separate current selection into a named
object, set origin to the 3D cursor / an explicit hinge coord, and export a clean
GLB. Face selection stays manual.
