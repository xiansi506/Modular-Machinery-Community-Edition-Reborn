/* Render-only masks. No changes to Level/chunk storage, collision, capabilities, or server state.
 * Official and SRG aliases are both explicit so the same guards work in development and production.
 * Optional Embeddium targets are transformed only when those classes actually load.
 */
function initializeCoreMod() {
    var O = Java.type('org.objectweb.asm.Opcodes');
    var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
    var Insn = Java.type('org.objectweb.asm.tree.InsnNode');
    var Var = Java.type('org.objectweb.asm.tree.VarInsnNode');
    var Call = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    var Field = Java.type('org.objectweb.asm.tree.FieldInsnNode');
    var Jump = Java.type('org.objectweb.asm.tree.JumpInsnNode');
    var Label = Java.type('org.objectweb.asm.tree.LabelNode');
    var Frame = Java.type('org.objectweb.asm.tree.FrameNode');
    var Method = Java.type('org.objectweb.asm.tree.MethodNode');
    var H = 'com/reborn/modularmachinery/render/WorldHidingHooks';
    var P = 'Lnet/minecraft/core/BlockPos;';
    var D = 'Lnet/minecraft/core/Direction;';
    var B = 'Lnet/minecraft/world/level/block/state/BlockState;';
    var F = 'Lnet/minecraft/world/level/material/FluidState;';
    var E = 'Lnet/minecraft/world/level/block/entity/BlockEntity;';
    var S = 'me/jellysquid/mods/sodium/client/';

    function load(list, slot) { list.add(new Var(O.ALOAD, slot)); }
    function call(list, name, desc) { list.add(new Call(O.INVOKESTATIC, H, name, desc, false)); }
    function guard(method, args, name, desc, result) {
        var list = new InsnList();
        args(list);
        call(list, name, desc);
        var next = new Label();
        list.add(new Jump(O.IFEQ, next));
        if (result === 'true') { list.add(new Insn(O.ICONST_1)); list.add(new Insn(O.IRETURN)); }
        else if (result === 'air' || result === 'emptyFluid') {
            call(list, result, '()' + (result === 'air' ? B : F));
            list.add(new Insn(O.ARETURN));
        } else list.add(new Insn(O.RETURN));
        list.add(next);
        list.add(new Frame(O.F_SAME, 0, null, 0, null));
        method.instructions.insert(list);
    }
    function view(node, fieldDesc) {
        var field = null;
        for (var i = 0; i < node.fields.size(); i++) {
            var candidate = node.fields.get(i);
            if (candidate.desc === fieldDesc && (candidate.access & O.ACC_STATIC) === 0) {
                if (field !== null) throw 'Ambiguous visibility world in ' + node.name;
                field = candidate;
            }
        }
        if (field === null) throw 'Missing visibility world in ' + node.name;
        node.interfaces.add(H + '$RenderView');
        var method = new Method(O.ACC_PUBLIC | O.ACC_SYNTHETIC, 'mmr$visibilityWorld', '()Ljava/lang/Object;', null, null);
        load(method.instructions, 0);
        method.instructions.add(new Field(O.GETFIELD, node.name, field.name, field.desc));
        method.instructions.add(new Insn(O.ARETURN));
        method.maxStack = 1; method.maxLocals = 1;
        node.methods.add(method);
    }
    function target(name, transform) {
        return { target: { type: 'CLASS', name: name.replace(/\//g, '.') }, transformer: transform };
    }
    function named(method, official, srg) { return method.name === official || method.name === srg; }
    function requireCount(node, count, expected) {
        if (count !== expected) throw 'World hiding: expected ' + expected + ' methods in ' + node.name + ', found ' + count;
        return node;
    }
    return {
        'render_region': target('net/minecraft/client/renderer/chunk/RenderChunkRegion', function(node) {
            view(node, 'Lnet/minecraft/world/level/Level;');
            var count = 0;
            for (var i = 0; i < node.methods.size(); i++) {
                var m = node.methods.get(i);
                if ((named(m, 'getBlockState', 'm_8055_') && m.desc === '(' + P + ')' + B)
                        || (named(m, 'getFluidState', 'm_6425_') && m.desc === '(' + P + ')' + F)) {
                    guard(m, function(l) { load(l, 0); load(l, 1); }, 'hiddenComponent', '(Ljava/lang/Object;' + P + ')Z',
                        m.desc.endsWith(B) ? 'air' : 'emptyFluid');
                    count++;
                }
            }
            return requireCount(node, count, 2);
        }),
        'block_dispatcher': target('net/minecraft/client/renderer/block/BlockRenderDispatcher', function(node) {
            var count = 0;
            for (var i = 0; i < node.methods.size(); i++) {
                var m = node.methods.get(i);
                if (named(m, 'renderBatched', 'm_234355_') || named(m, 'renderBreakingTexture', 'm_110918_')) {
                    guard(m, function(l) { load(l, 3); load(l, 2); }, 'hiddenModel', '(Ljava/lang/Object;' + P + ')Z');
                    count++;
                } else if (named(m, 'renderLiquid', 'm_234363_')) {
                    guard(m, function(l) { load(l, 2); load(l, 1); }, 'hiddenModel', '(Ljava/lang/Object;' + P + ')Z');
                    count++;
                }
            }
            return requireCount(node, count, 5);
        }),
        'block_entities': target('net/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher', function(node) {
            var count = 0;
            for (var i = 0; i < node.methods.size(); i++) {
                var m = node.methods.get(i);
                if (named(m, 'render', 'm_112267_') && m.desc.indexOf('(' + E + 'F') === 0) {
                    guard(m, function(l) { load(l, 1); }, 'hiddenBlockEntity', '(' + E + ')Z');
                    count++;
                }
            }
            return requireCount(node, count, 1);
        }),
        'neighbor_faces': target('net/minecraft/world/level/block/Block', function(node) {
            var count = 0;
            for (var i = 0; i < node.methods.size(); i++) {
                var m = node.methods.get(i);
                if (named(m, 'shouldRenderFace', 'm_152444_')) {
                    guard(m, function(l) { load(l, 1); load(l, 4); }, 'hiddenModel', '(Ljava/lang/Object;' + P + ')Z', 'true');
                    count++;
                }
            }
            return requireCount(node, count, 1);
        }),
        'embeddium_slice': target(S + 'world/WorldSlice', function(node) {
            view(node, 'Lnet/minecraft/client/multiplayer/ClientLevel;');
            var count = 0;
            for (var i = 0; i < node.methods.size(); i++) {
                var m = node.methods.get(i);
                if (m.name === 'getBlockState' && m.desc === '(III)' + B) {
                    guard(m, function(l) {
                        load(l, 0); l.add(new Var(O.ILOAD, 1)); l.add(new Var(O.ILOAD, 2)); l.add(new Var(O.ILOAD, 3));
                    }, 'hiddenComponentAt', '(Ljava/lang/Object;III)Z', 'air');
                    count++;
                }
            }
            return requireCount(node, count, 1);
        }),
        'embeddium_models': target(S + 'render/chunk/compile/pipeline/BlockRenderer', function(node) {
            var count = 0;
            for (var i = 0; i < node.methods.size(); i++) {
                var m = node.methods.get(i);
                if (m.name === 'renderModel') {
                    guard(m, function(l) {
                        var context = S + 'render/chunk/compile/pipeline/BlockRenderContext';
                        load(l, 1); l.add(new Call(O.INVOKEVIRTUAL, context, 'world', '()L' + S + 'world/WorldSlice;', false));
                        load(l, 1); l.add(new Call(O.INVOKEVIRTUAL, context, 'pos', '()' + P, false));
                    }, 'hiddenModel', '(Ljava/lang/Object;' + P + ')Z');
                    count++;
                }
            }
            return requireCount(node, count, 1);
        }),
        'embeddium_fluids': target(S + 'render/chunk/compile/pipeline/FluidRenderer', function(node) {
            var count = 0;
            for (var i = 0; i < node.methods.size(); i++) {
                var m = node.methods.get(i);
                if (m.name === 'render') {
                    guard(m, function(l) { load(l, 1); load(l, 3); }, 'hiddenModel', '(Ljava/lang/Object;' + P + ')Z');
                    count++;
                }
            }
            return requireCount(node, count, 1);
        }),
        'embeddium_faces': target(S + 'render/chunk/compile/pipeline/BlockOcclusionCache', function(node) {
            var count = 0;
            for (var i = 0; i < node.methods.size(); i++) {
                var m = node.methods.get(i);
                if (m.name === 'shouldDrawSide') {
                    guard(m, function(l) { load(l, 2); load(l, 3); load(l, 4); }, 'hiddenNeighbor',
                        '(Ljava/lang/Object;' + P + D + ')Z', 'true');
                    count++;
                }
            }
            return requireCount(node, count, 1);
        })
    };
}
