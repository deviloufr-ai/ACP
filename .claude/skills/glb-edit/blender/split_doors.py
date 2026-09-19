# Blender helpers for turning car.glb's fused body into animatable door nodes.
#
# Run inside Blender: open the Text Editor, load this file, and either press "Run
# Script" (calls demo() which just prints guidance) or paste individual calls into
# the Python Console. Or headless:
#     blender car.blend --python split_doors.py
#
# The actual DOOR FACE SELECTION is manual — there is no reliable way to auto-detect
# a door in a fused shell. These helpers automate the mechanical steps around it:
# separating the current selection, placing the origin on the hinge, and exporting.
#
# Typical flow (see SKILL.md for the full walkthrough):
#   1. Import app/src/main/assets/car.glb (File > Import > glTF 2.0).
#   2. Select body > Tab (Edit Mode) > select the door faces (hover + L, or box).
#   3. In the Python Console:  separate_selection("DoorFL")
#   4. Snap 3D cursor to the hinge edge (select the hinge edge, Shift+S > Cursor to
#      Selected), then:  set_origin_to_cursor("DoorFL")
#      (or give the hinge point directly: set_origin("DoorFL", (x, y, z)))
#   5. export_glb(r"D:/android car launcher/app/src/main/assets/car.glb")
#
# After export, author the open/close animation with the Node tooling:
#   node scripts/animate-rotate.mjs --nodes "DoorFL" --axis y --degrees 65 \
#       --mode pingpong --pivot origin --duration 1.2 --name "DoorFL"

import bpy


def separate_selection(new_name):
    """In Edit Mode with door faces selected: split them into a new object."""
    if bpy.context.mode != 'EDIT_MESH':
        raise RuntimeError("Enter Edit Mode and select the door faces first.")
    before = set(bpy.data.objects.keys())
    bpy.ops.mesh.separate(type='SELECTED')
    bpy.ops.object.mode_set(mode='OBJECT')
    new = [n for n in bpy.data.objects.keys() if n not in before]
    if not new:
        raise RuntimeError("Nothing was separated — was any face selected?")
    obj = bpy.data.objects[new[-1]]
    obj.name = new_name
    print(f"separated '{new_name}' ({len(obj.data.vertices)} verts)")
    return obj


def set_origin_to_cursor(obj_name):
    """Set an object's origin to the current 3D cursor (place cursor on the hinge)."""
    obj = bpy.data.objects[obj_name]
    bpy.ops.object.select_all(action='DESELECT')
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj
    bpy.ops.object.origin_set(type='ORIGIN_CURSOR')
    print(f"'{obj_name}' origin -> 3D cursor {tuple(bpy.context.scene.cursor.location)}")


def set_origin(obj_name, hinge_xyz):
    """Set an object's origin to an explicit world hinge point (x, y, z)."""
    scene = bpy.context.scene
    saved = tuple(scene.cursor.location)
    scene.cursor.location = hinge_xyz
    set_origin_to_cursor(obj_name)
    scene.cursor.location = saved


def export_glb(path):
    """Export the whole scene to a .glb (Y-up, matching the launcher's expectation)."""
    bpy.ops.export_scene.gltf(
        filepath=path,
        export_format='GLB',
        export_yup=True,
        export_apply=True,          # apply modifiers
        use_selection=False,
    )
    print(f"exported {path}")


def demo():
    print(__doc__)


if __name__ == "__main__":
    demo()
