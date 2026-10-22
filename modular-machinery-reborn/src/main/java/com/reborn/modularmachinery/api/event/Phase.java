package com.reborn.modularmachinery.api.event;

/**
 * Which half of a paired lifecycle event is being posted.
 *
 * <p>This is the original's {@code github.kasuminova.mmce.common.event.Phase}, kept because the original
 * posted both halves and addons wrote handlers that branch on it. A {@code START} notification is posted
 * before the controller acts, an {@code END} notification after it.
 */
public enum Phase {
    START,
    END
}
