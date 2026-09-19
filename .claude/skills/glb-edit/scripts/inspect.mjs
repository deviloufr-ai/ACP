// Inspect a GLB: scenes, nodes (with TRS + mesh + children), meshes (vert counts
// + local bounding box), materials, animations, textures.
// Usage: node scripts/inspect.mjs <path-to.glb>
import { NodeIO } from '@gltf-transform/core';
import { ALL_EXTENSIONS } from '@gltf-transform/extensions';

const path = process.argv[2];
if (!path) { console.error('usage: node inspect.mjs <file.glb>'); process.exit(1); }

const io = new NodeIO().registerExtensions(ALL_EXTENSIONS);
const doc = await io.read(path);
const root = doc.getRoot();

function bbox(mesh) {
  let min = [Infinity, Infinity, Infinity], max = [-Infinity, -Infinity, -Infinity];
  const el = [0, 0, 0];
  for (const p of mesh.listPrimitives()) {
    const pos = p.getAttribute('POSITION'); if (!pos) continue;
    for (let i = 0; i < pos.getCount(); i++) {
      pos.getElement(i, el);
      for (let k = 0; k < 3; k++) { if (el[k] < min[k]) min[k] = el[k]; if (el[k] > max[k]) max[k] = el[k]; }
    }
  }
  const center = min.map((v, k) => +((v + max[k]) / 2).toFixed(3));
  const size = min.map((v, k) => +((max[k] - v)).toFixed(3));
  return { center, size };
}

console.log('=== SCENES ===');
for (const s of root.listScenes()) console.log('scene:', JSON.stringify(s.getName()));

console.log('\n=== NODES (name | mesh | children | T R S) ===');
const nodes = root.listNodes();
console.log('total nodes:', nodes.length);
for (const n of nodes) {
  const t = n.getTranslation().map(x => +x.toFixed(3));
  const r = n.getRotation().map(x => +x.toFixed(3));
  const s = n.getScale().map(x => +x.toFixed(3));
  console.log(`- ${JSON.stringify(n.getName())} | mesh=${n.getMesh() ? JSON.stringify(n.getMesh().getName()) : '-'} | children=${n.listChildren().length} | T=${t} R=${r} S=${s}`);
}

console.log('\n=== MESHES (verts | local bbox center / size) ===');
for (const m of root.listMeshes()) {
  const prims = m.listPrimitives();
  let verts = 0; for (const p of prims) { const pos = p.getAttribute('POSITION'); if (pos) verts += pos.getCount(); }
  const bb = bbox(m);
  console.log(`- ${JSON.stringify(m.getName())} | prims=${prims.length} | verts=${verts} | center=${bb.center} size=${bb.size}`);
}

console.log('\n=== MATERIALS ===');
for (const mat of root.listMaterials()) console.log('-', JSON.stringify(mat.getName()));

console.log('\n=== ANIMATIONS ===');
const anims = root.listAnimations();
console.log('total animations:', anims.length);
for (const a of anims) {
  const chans = a.listChannels();
  const targets = chans.map(c => `${c.getTargetNode()?.getName()}.${c.getTargetPath()}`);
  console.log(`- ${JSON.stringify(a.getName())} | channels=${chans.length} [${targets.join(', ')}]`);
}

console.log('\n=== TEXTURES ===');
for (const tex of root.listTextures()) {
  const img = tex.getImage();
  console.log(`- ${JSON.stringify(tex.getName())} | ${tex.getMimeType()} | ${img ? (img.byteLength / 1024).toFixed(0) + ' KB' : '?'}`);
}
