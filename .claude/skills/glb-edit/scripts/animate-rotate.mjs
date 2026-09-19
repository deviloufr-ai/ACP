// Author a rotation animation on one or more nodes of a GLB.
//
// Rotates each target node about a chosen axis and pivot, sampled into keyframes.
// Two shapes:
//   * spin   : 0 -> degrees, LINEAR, loops seamlessly (use degrees=360 for wheels)
//   * pingpong: 0 -> degrees -> 0 (use for a door opening then closing)
//
// Pivot handling: glTF node rotation is always about the node's LOCAL origin.
// When a part's geometry is offset from that origin (e.g. a wheel whose verts are
// centered at 1.25,3.8,-3.8), rotating the node swings it in an arc. Pass
// --pivot center (default) and the script wraps the mesh in a pivot child so it
// spins in place. Pass --pivot origin to rotate the node itself (use this for a
// door whose hinge already sits at the node origin), or --pivot x,y,z for an
// explicit local pivot (a hinge line).
//
// Usage:
//   node scripts/animate-rotate.mjs \
//     --in ../../../app/src/main/assets/car.glb \
//     --out ../../../app/src/main/assets/car.glb \
//     --nodes "G-Object,G-Object.001,G-Object.002,G-Object.003" \
//     --axis x --degrees 360 --duration 3 --mode spin \
//     --pivot center --name "WheelSpin"
//
// Flags:
//   --in <glb>            input file (required)
//   --out <glb>           output file (required; may equal --in, a .bak is written)
//   --nodes "a,b,c"       comma-separated node names to animate
//   --nodes-regex <re>    OR select nodes whose name matches this regex
//   --axis x|y|z          rotation axis in the node's local space (default y)
//   --degrees <n>         total rotation in degrees (default 360)
//   --duration <s>        seconds for the whole clip (default 3)
//   --mode spin|pingpong  spin loops; pingpong opens then closes (default spin)
//   --pivot center|origin|x,y,z   rotation pivot (default center)
//   --keyframes <n>       samples across the clip (auto-raised so steps <=90 deg)
//   --name <str>          animation name (default "Rotate")
//   --flip-mirrored       negate angle for nodes with negative-determinant scale
//                         (keeps mirrored L/R wheels spinning the same way)
import { NodeIO } from '@gltf-transform/core';
import { ALL_EXTENSIONS } from '@gltf-transform/extensions';
import { existsSync, copyFileSync } from 'node:fs';

function arg(name, def) {
  const i = process.argv.indexOf(`--${name}`);
  if (i === -1) return def;
  const v = process.argv[i + 1];
  return (v === undefined || v.startsWith('--')) ? true : v;
}

const inPath = arg('in');
const outPath = arg('out');
if (!inPath || !outPath) { console.error('need --in and --out'); process.exit(1); }

const axisName = String(arg('axis', 'y')).toLowerCase();
const axis = { x: [1, 0, 0], y: [0, 1, 0], z: [0, 0, 1] }[axisName];
if (!axis) { console.error('--axis must be x|y|z'); process.exit(1); }
const degrees = parseFloat(arg('degrees', '360'));
const duration = parseFloat(arg('duration', '3'));
const mode = String(arg('mode', 'spin'));
const pivotArg = String(arg('pivot', 'center'));
const animName = String(arg('name', 'Rotate'));
const flipMirrored = arg('flip-mirrored', false) === true;

const namesArg = arg('nodes');
const regexArg = arg('nodes-regex');
if (!namesArg && !regexArg) { console.error('need --nodes or --nodes-regex'); process.exit(1); }
const wantNames = namesArg ? String(namesArg).split(',').map(s => s.trim()).filter(Boolean) : null;
const wantRegex = regexArg ? new RegExp(String(regexArg)) : null;

const io = new NodeIO().registerExtensions(ALL_EXTENSIONS);
const doc = await io.read(inPath);
const root = doc.getRoot();

function meshBBoxCenter(mesh) {
  let min = [Infinity, Infinity, Infinity], max = [-Infinity, -Infinity, -Infinity];
  const el = [0, 0, 0];
  for (const p of mesh.listPrimitives()) {
    const pos = p.getAttribute('POSITION'); if (!pos) continue;
    for (let i = 0; i < pos.getCount(); i++) {
      pos.getElement(i, el);
      for (let k = 0; k < 3; k++) { if (el[k] < min[k]) min[k] = el[k]; if (el[k] > max[k]) max[k] = el[k]; }
    }
  }
  return [(min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2];
}

// quaternion for angle (rad) about unit axis
function quat(angleRad, ax) {
  const h = angleRad / 2, s = Math.sin(h);
  return [ax[0] * s, ax[1] * s, ax[2] * s, Math.cos(h)];
}

// keyframe count: enough so each slerp step is <=90 deg (quaternions can't
// represent a >180 deg step and slerp takes the short arc)
const span = mode === 'pingpong' ? Math.max(Math.abs(degrees), 1) : Math.abs(degrees);
let kf = parseInt(arg('keyframes', '0'), 10) || 0;
const minKf = Math.max(2, Math.ceil(span / 90) + 1);
if (kf < minKf) kf = minKf;

const anim = doc.createAnimation(animName);
const buffer = root.listBuffers()[0] || doc.createBuffer();

const selected = root.listNodes().filter(n => {
  const nm = n.getName();
  if (wantNames) return wantNames.includes(nm);
  return wantRegex.test(nm);
});
if (selected.length === 0) { console.error('no matching nodes'); process.exit(1); }

for (const node of selected) {
  const mesh = node.getMesh();

  // resolve pivot (local space of the geometry)
  let pivot = [0, 0, 0];
  if (pivotArg === 'origin') pivot = [0, 0, 0];
  else if (pivotArg === 'center') pivot = mesh ? meshBBoxCenter(mesh) : [0, 0, 0];
  else pivot = pivotArg.split(',').map(Number);

  // choose the node we actually animate
  let target = node;
  const needPivot = pivot.some(v => Math.abs(v) > 1e-6);
  if (needPivot && mesh) {
    // wrap: node -> pivotNode(T=pivot) -> holder(T=-pivot, mesh). At rest this is
    // identity, so world position is unchanged; animating pivotNode.rotation spins
    // the mesh about `pivot` in the geometry's local frame.
    const pivotNode = doc.createNode(`${node.getName()}__pivot`).setTranslation(pivot);
    const holder = doc.createNode(`${node.getName()}__mesh`).setTranslation(pivot.map(v => -v)).setMesh(mesh);
    node.setMesh(null);
    pivotNode.addChild(holder);
    node.addChild(pivotNode);
    target = pivotNode;
  }

  // sign: optionally flip for mirrored (negative determinant) nodes
  let sign = 1;
  if (flipMirrored) {
    const s = node.getScale();
    if (s[0] * s[1] * s[2] < 0) sign = -1;
  }

  // build keyframes
  const times = new Float32Array(kf);
  const values = new Float32Array(kf * 4);
  for (let i = 0; i < kf; i++) {
    const u = i / (kf - 1);              // 0..1
    times[i] = u * duration;
    let deg;
    if (mode === 'pingpong') deg = degrees * (u <= 0.5 ? (u * 2) : (2 - u * 2));
    else deg = degrees * u;             // spin
    const q = quat(sign * deg * Math.PI / 180, axis);
    values.set(q, i * 4);
  }

  const input = doc.createAccessor(`${target.getName()}_time`).setType('SCALAR').setArray(times).setBuffer(buffer);
  const output = doc.createAccessor(`${target.getName()}_rot`).setType('VEC4').setArray(values).setBuffer(buffer);
  const sampler = doc.createAnimationSampler().setInput(input).setOutput(output).setInterpolation('LINEAR');
  const channel = doc.createAnimationChannel().setTargetNode(target).setTargetPath('rotation').setSampler(sampler);
  anim.addSampler(sampler).addChannel(channel);
  console.log(`animated ${JSON.stringify(node.getName())} -> target ${JSON.stringify(target.getName())} | pivot=${pivot.map(v=>+v.toFixed(3))} sign=${sign}`);
}

if (existsSync(outPath) && outPath === inPath) {
  copyFileSync(outPath, outPath + '.bak');
  console.log(`backup written: ${outPath}.bak`);
}
await io.write(outPath, doc);
console.log(`wrote ${outPath} | animation "${animName}" | ${selected.length} node(s) | ${kf} keyframes | ${duration}s ${mode}`);
