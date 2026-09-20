# Blender startup script for the "I set up, you select" door-splitting flow.
# Launch with:
#   blender --python door_tool_startup.py
#
# It wipes the default scene, imports car.glb, frames it, enters Edit Mode on the
# body with nothing selected, and registers a "Door Tool" panel in the 3D viewport
# sidebar (press N) with buttons for the mechanical steps. You select the door
# faces; the buttons do separate / set-hinge / export.

import bpy

CAR_GLB = r"D:/android car launcher/app/src/main/assets/car.glb"

# ---------------------------------------------------------------- scene setup
def wipe_scene():
    bpy.ops.object.select_all(action='SELECT')
    bpy.ops.object.delete()
    for block in (bpy.data.meshes, bpy.data.materials):
        for b in list(block):
            if b.users == 0:
                block.remove(b)

def import_car():
    bpy.ops.import_scene.gltf(filepath=CAR_GLB)

def pick_body():
    """Return the largest mesh object (the fused car body)."""
    meshes = [o for o in bpy.data.objects if o.type == 'MESH']
    if not meshes:
        return None
    return max(meshes, key=lambda o: len(o.data.vertices))

def frame_and_edit():
    body = pick_body()
    if not body:
        return
    bpy.ops.object.select_all(action='DESELECT')
    body.select_set(True)
    bpy.context.view_layer.objects.active = body
    # frame + enter edit mode inside a 3D viewport context
    for area in bpy.context.screen.areas:
        if area.type == 'VIEW_3D':
            for region in area.regions:
                if region.type == 'WINDOW':
                    with bpy.context.temp_override(area=area, region=region):
                        bpy.ops.view3d.view_all(center=False)
                        bpy.ops.object.mode_set(mode='EDIT')
                        bpy.ops.mesh.select_all(action='DESELECT')
                        # face select mode
                        bpy.ops.mesh.select_mode(type='FACE')
                    return

# ---------------------------------------------------------------- operators
class DOOR_OT_separate(bpy.types.Operator):
    bl_idname = "door.separate"
    bl_label = "Separate as Door"
    bl_description = "Split the currently selected faces into a new object named by the field above"
    def execute(self, context):
        name = context.scene.door_name.strip() or "Door"
        if context.mode != 'EDIT_MESH':
            self.report({'ERROR'}, "Enter Edit Mode and select the door faces first.")
            return {'CANCELLED'}
        before = set(bpy.data.objects.keys())
        bpy.ops.mesh.separate(type='SELECTED')
        bpy.ops.object.mode_set(mode='OBJECT')
        new = [n for n in bpy.data.objects.keys() if n not in before]
        if not new:
            self.report({'ERROR'}, "Nothing separated - select some door faces first.")
            return {'CANCELLED'}
        obj = bpy.data.objects[new[-1]]
        obj.name = name
        bpy.ops.object.select_all(action='DESELECT')
        obj.select_set(True)
        context.view_layer.objects.active = obj
        self.report({'INFO'}, f"Separated '{name}' ({len(obj.data.vertices)} verts). Now set the hinge.")
        return {'FINISHED'}

class DOOR_OT_set_hinge(bpy.types.Operator):
    bl_idname = "door.set_hinge"
    bl_label = "Set Hinge = 3D Cursor"
    bl_description = "Set the active door's origin to the 3D cursor (place the cursor on the hinge edge first)"
    def execute(self, context):
        obj = context.view_layer.objects.active
        if not obj:
            self.report({'ERROR'}, "Select the door object first.")
            return {'CANCELLED'}
        if context.mode != 'OBJECT':
            bpy.ops.object.mode_set(mode='OBJECT')
        bpy.ops.object.origin_set(type='ORIGIN_CURSOR')
        self.report({'INFO'}, f"'{obj.name}' origin -> hinge {tuple(round(v,3) for v in context.scene.cursor.location)}")
        return {'FINISHED'}

class DOOR_OT_export(bpy.types.Operator):
    bl_idname = "door.export"
    bl_label = "Export GLB"
    bl_description = "Export the whole scene back over car.glb (Y-up)"
    def execute(self, context):
        path = context.scene.export_path
        bpy.ops.export_scene.gltf(filepath=path, export_format='GLB',
                                  export_yup=True, export_apply=True, use_selection=False)
        self.report({'INFO'}, f"Exported {path}")
        return {'FINISHED'}

class DOOR_PT_panel(bpy.types.Panel):
    bl_label = "Door Tool"
    bl_space_type = 'VIEW_3D'
    bl_region_type = 'UI'
    bl_category = "Door Tool"
    def draw(self, context):
        col = self.layout.column(align=True)
        col.label(text="1. Edit Mode + select door faces")
        col.prop(context.scene, "door_name", text="Name")
        col.operator("door.separate", icon='MOD_EXPLODE')
        col.separator()
        col.label(text="2. Cursor to hinge edge, then:")
        col.operator("door.set_hinge", icon='PIVOT_CURSOR')
        col.separator()
        col.label(text="3. When all doors done:")
        col.operator("door.export", icon='EXPORT')

_classes = (DOOR_OT_separate, DOOR_OT_set_hinge, DOOR_OT_export, DOOR_PT_panel)

def register():
    bpy.types.Scene.door_name = bpy.props.StringProperty(name="Door name", default="DoorFL")
    bpy.types.Scene.export_path = bpy.props.StringProperty(name="Export path", default=CAR_GLB, subtype='FILE_PATH')
    for c in _classes:
        bpy.utils.register_class(c)

# ---------------------------------------------------------------- run
wipe_scene()
import_car()
register()
frame_and_edit()
print("Door Tool ready. Press N in the 3D viewport for the 'Door Tool' tab.")
