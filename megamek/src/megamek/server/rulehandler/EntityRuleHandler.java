/*
 * MegaMek -
 * Copyright (C) 2018 - The MegaMek Team
 *
 *  This program is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License as published by the Free
 *  Software Foundation; either version 2 of the License, or (at your option)
 *  any later version.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 *  or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 *  for more details.
 */

package megamek.server.rulehandler;

import java.util.ArrayList;
import java.util.List;

import megamek.common.Entity;
import megamek.common.IEntityRemovalConditions;
import megamek.common.net.Packet;

/**
 * Base class for {@link RuleHandler}s involving a particular {@link Entity}.
 * 
 * @author Neoancient
 *
 */
public abstract class EntityRuleHandler extends RuleHandler {
    
    protected final Entity entity;
    
    protected EntityRuleHandler(Entity entity) {
        this.entity = entity;
    }
    
    /**
     * Creates a packet detailing the removal of an entity.
     *
     * @param entityId  - the {@code int} ID of the entity being removed.
     * @return A {@link Packet} to be sent to clients.
     */
    protected Packet createRemoveEntityPacket(int entityId) {
        return createRemoveEntityPacket(entityId, IEntityRemovalConditions.REMOVE_SALVAGEABLE);
    }
    
    /**
     * Creates a packet detailing the removal of an entity.
     *
     * @param entityId  - the ID of the entity being removed.
     * @param condition - the condition the unit was in.
     * @return A {@link Packet} to be sent to clients.
     * @throws IllegalArgumentException if condition is not a value of a constant
     *         in {@link IEntityRemovalConditions}
     */
    protected Packet createRemoveEntityPacket(int entityId, int condition) {
        ArrayList<Integer> ids = new ArrayList<Integer>(1);
        ids.add(entityId);
        return createRemoveEntityPacket(ids, condition);
    }

    /**
     * Creates a packet detailing the removal of a list of entities.
     *
     * @param entityIds - a {@link List} of IDs of each entity being removed.
     * @param condition - the condition the units were in.
     * @return A {@link Packet Packet} to be sent to clients.
     * @throws IllegalArgumentException if condition is not a value of a constant
     *         in {@link IEntityRemovalConditions}
     */
    protected Packet createRemoveEntityPacket(List<Integer> entityIds,
                                            int condition) {
        if ((condition != IEntityRemovalConditions.REMOVE_UNKNOWN)
            && (condition != IEntityRemovalConditions.REMOVE_IN_RETREAT)
            && (condition != IEntityRemovalConditions.REMOVE_PUSHED)
            && (condition != IEntityRemovalConditions.REMOVE_SALVAGEABLE)
            && (condition != IEntityRemovalConditions.REMOVE_EJECTED)
            && (condition != IEntityRemovalConditions.REMOVE_CAPTURED)
            && (condition != IEntityRemovalConditions.REMOVE_DEVASTATED)
            && (condition != IEntityRemovalConditions.REMOVE_NEVER_JOINED)) {
            throw new IllegalArgumentException("Unknown unit condition: "
                                               + condition);
        }
        Object[] array = new Object[2];
        array[0] = entityIds;
        array[1] = new Integer(condition);
        return new Packet(Packet.COMMAND_ENTITY_REMOVE, array);
    }

}
