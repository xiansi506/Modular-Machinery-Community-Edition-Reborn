package com.reborn.modularmachinery.selection;

import net.minecraft.core.BlockPos;
import java.util.*;

/** Ordered selections with bounded, atomic history. A box edit is one undo step. */
public final class StructureSelection {
    public static final int MAX_BLOCKS = 32768;
    private static final int MAX_HISTORY = 32, MAX_HISTORY_POSITIONS = 262144;
    public enum Operation { TOGGLE, ADD, REMOVE }
    private final LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
    private final Deque<List<BlockPos>> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    public static StructureSelection of(Collection<BlockPos> positions) {
        StructureSelection selection = new StructureSelection();
        positions.forEach(pos -> selection.positions.add(pos.immutable())); return selection;
    }
    public boolean toggle(BlockPos pos) { apply(List.of(pos), Operation.TOGGLE); return contains(pos); }
    public void apply(Collection<BlockPos> changed, Operation operation) {
        LinkedHashSet<BlockPos> next = new LinkedHashSet<>(positions);
        for (BlockPos position : new LinkedHashSet<>(changed)) {
            BlockPos pos = position.immutable();
            switch (operation) {
                case ADD -> next.add(pos);
                case REMOVE -> next.remove(pos);
                case TOGGLE -> { if (!next.remove(pos)) next.add(pos); }
            }
        }
        if (next.size() > MAX_BLOCKS) throw new IllegalArgumentException("Selection exceeds " + MAX_BLOCKS + " blocks");
        if (positions.equals(next)) return;
        remember(undo, positions()); redo.clear(); positions.clear(); positions.addAll(next);
    }
    private static void remember(Deque<List<BlockPos>> history, List<BlockPos> snapshot) {
        history.addLast(snapshot); int size = history.stream().mapToInt(List::size).sum();
        while (history.size() > MAX_HISTORY || (history.size() > 1 && size > MAX_HISTORY_POSITIONS)) size -= history.removeFirst().size();
    }
    public boolean undo() { return restore(undo, redo); }
    public boolean redo() { return restore(redo, undo); }
    private boolean restore(Deque<List<BlockPos>> from, Deque<List<BlockPos>> to) {
        if (from.isEmpty()) return false;
        remember(to, positions()); positions.clear(); positions.addAll(from.removeLast()); return true;
    }
    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }
    public boolean contains(BlockPos pos) { return positions.contains(pos); }
    public int size() { return positions.size(); }
    public boolean isEmpty() { return positions.isEmpty(); }
    public List<BlockPos> positions() { return new ArrayList<>(positions); }
    public void clear() { if (!positions.isEmpty()) apply(positions(), Operation.REMOVE); }
    public String boundsText() {
        if (positions.isEmpty()) return "-";
        int x=Integer.MAX_VALUE,y=x,z=x,X=Integer.MIN_VALUE,Y=X,Z=X;
        for (BlockPos pos:positions) { x=Math.min(x,pos.getX());y=Math.min(y,pos.getY());z=Math.min(z,pos.getZ());X=Math.max(X,pos.getX());Y=Math.max(Y,pos.getY());Z=Math.max(Z,pos.getZ()); }
        return new BlockPos(x,y,z).toShortString()+" → "+new BlockPos(X,Y,Z).toShortString();
    }
    @Override public String toString() { return positions.toString(); }
}