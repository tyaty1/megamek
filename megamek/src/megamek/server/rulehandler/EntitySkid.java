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
import java.util.Iterator;
import java.util.Vector;

import megamek.common.Aero;
import megamek.common.Building;
import megamek.common.Compute;
import megamek.common.Coords;
import megamek.common.CriticalSlot;
import megamek.common.Entity;
import megamek.common.EntityMovementMode;
import megamek.common.EntityMovementType;
import megamek.common.GameTurn;
import megamek.common.HitData;
import megamek.common.IEntityRemovalConditions;
import megamek.common.IGame;
import megamek.common.IHex;
import megamek.common.Infantry;
import megamek.common.Mech;
import megamek.common.MoveStep;
import megamek.common.PilotingRollData;
import megamek.common.Protomech;
import megamek.common.QuadVee;
import megamek.common.Report;
import megamek.common.Tank;
import megamek.common.TargetRoll;
import megamek.common.Terrains;
import megamek.common.ToHitData;
import megamek.common.actions.ChargeAttackAction;
import megamek.common.annotations.Nullable;
import megamek.common.logging.DefaultMmLogger;
import megamek.common.options.OptionsConstants;
import megamek.server.Server;

/**
 * Handler for Entity skids and sideslips.
 * 
 * Rules: Total Warfare, pp. 61-66 (Skidding), 67-68 (Sideslips)
 * 
 * @author Neoancient
 *
 */
public class EntitySkid extends EntityRuleHandler {
    
    final private Coords start;
    final private int startElevation;
    final private int direction;
    final private int origDistance;
    final private MoveStep step;
    final private EntityMovementType moveType;
    final private boolean flip;
    
    // Needed for access to the transitional RuleHandler adapters. Once they have been extracted by
    // refactoring the wrapped code, this can be removed.
    final private Server server;
    
    /**
     * Indicates whether the {@link Entity Entity} is removed from the game as a result of resolving the skid
     */
    private boolean removeFromPlay = false;

    /**
     * @param entity    the unit which should skid
     * @param start     the coordinates of the hex the unit was in prior to skidding
     * @param elevation the elevation of the unit
     * @param direction the direction of the skid
     * @param distance  the number of hexes skidded
     * @param step      the MoveStep which caused the skid
     * @param server    The server instance; needed temporarilty to access transitional code
     * @return true if the entity was removed from play
     */
    public EntitySkid(Entity entity, Coords start, int elevation,
            int direction, int distance, MoveStep step,
            EntityMovementType moveType, Server server) {
        this(entity, start, elevation, direction, distance,
                step, moveType, false, server);
    }
    
    /**
     * @param entity    the unit which should skid
     * @param start     the coordinates of the hex the unit was in prior to skidding
     * @param elevation the elevation of the unit
     * @param direction the direction of the skid
     * @param distance  the number of hexes skidded
     * @param step      the MoveStep which caused the skid
     * @param flip      whether the skid resulted from a failure maneuver result of major skid
     * @param server    The server instance; needed temporarilty to access transitional code
     * @return true if the entity was removed from play
     */
    public EntitySkid(Entity entity, Coords start, int elevation,
            int direction, int distance, MoveStep step,
            EntityMovementType moveType, boolean flip, Server server) {
        super(entity);
        this.start = start;
        this.startElevation = elevation;
        this.direction = direction;
        this.origDistance = distance;
        this.step = step;
        this.moveType = moveType;
        this.flip = flip;
        this.server = server;
    }
    
    /**
     * After the skid is resolved, this indicates whether the skid results in the {@link Entity Entity} 
     * being removed from the game, either by destruction or by leaving the board.
     */
    public boolean isRemovedFromPlay() {
        return removeFromPlay;
    }

    private Coords nextPos;
    private Coords curPos;
    private IHex curHex;
    private IHex nextHex;
    private int distRemaining;
    private int currentElevation;
    private int nextElevation;
    private int curAltitude;
    private int nextAltitude;
    private int skidDistance; // Actual distance moved
    
    @Override
    public void resolve(IGame game) {
        final String METHOD_NAME = "resolve(IGame)"; //$NON-NLS-1$
        initStartingValues(game);
        Report r;
        // Flipping vehicles take tonnage/10 points of damage for every hex they enter.
        int flipDamage = (int)Math.ceil(entity.getWeight() / 10.0);
        while (!entity.isDoomed() && (distRemaining > 0)) {
            if (!updatePosition(game)) {
                break;
            }
            
            if (checkTerrainCollision(game)) {
                processTerrainCollision(game);
                break;
            }

            // did we hit a dropship. Oww!
            // Taharqa: The rules on how to handle this are completely missing,
            // so I am assuming
            // we assign damage as per an accidental charge, but do not displace
            // the dropship and
            // end the skid
            Entity crashDropship = checkDropshipCollision(game);
            if ((null != crashDropship) && !processDropshipCollision(crashDropship, game)) {
                break;
            }
            
            if (fallOffCliff(game)) {
                break;
            }

            if (doAccidentalCharge(game)) {
                break;
            }

            // Update entity position and elevation
            entity.setPosition(nextPos);
            entity.setElevation(nextElevation);
            process(server.new EntityDisplacementMinefieldCheck(entity, curPos,
                    nextPos, nextElevation), game);
            skidDistance++;

            // Check for collapse of any building the entity might be on
            Building roof = game.getBoard().getBuildingAt(nextPos);
            if (roof != null) {
                Server.BuildingCollapseCheck check = server.new BuildingCollapseCheck(roof,
                        game.getPositionMap(), nextPos, true);
                process(check, game);
                if (check.isCollapsed()) {
                    break; // stop skidding if the building collapsed
                }
            }

            // Can the skiding entity enter the next hex from this?
            // N.B. can skid along roads.
            if ((entity.isLocationProhibited(start) || entity
                    .isLocationProhibited(nextPos))
                    && !Compute.canMoveOnPavement(game, curPos, nextPos, step)) {
                // Update report.
                r = new Report(2040);
                r.subject = entity.getId();
                r.indent();
                r.add(nextPos.getBoardNum(), true);
                addReport(r);

                // If the prohibited terrain is water, entity is destroyed
                if ((nextHex.terrainLevel(Terrains.WATER) > 0)
                        && (entity instanceof Tank)
                        && (entity.getMovementMode() != EntityMovementMode.HOVER)
                        && (entity.getMovementMode() != EntityMovementMode.WIGE)) {
                    process(server.new EntityDestruction(entity,
                            "skidded into a watery grave", false, true), game);
                }

                // otherwise, damage is weight/5 in 5pt clusters
                int damage = ((int) entity.getWeight() + 4) / 5;
                while (damage > 0) {
                    process(server.new EntityDamage(entity, entity.rollHitLocation(
                            ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT),
                            Math.min(5, damage)), game);
                    damage -= 5;
                }
                // and unit is immobile
                if (entity instanceof Tank) {
                    ((Tank) entity).immobilize();
                }

                // Stay in the current hex and stop skidding.
                break;
            }

            if ((nextHex.terrainLevel(Terrains.WATER) > 0)
                && (entity.getMovementMode() != EntityMovementMode.HOVER)
                && (entity.getMovementMode() != EntityMovementMode.WIGE)) {
                // water ends the skid
                break;
            }

            // check for breaking magma crust
            if ((nextHex.terrainLevel(Terrains.MAGMA) == 1)
                && (nextElevation == 0)) {
                int roll = Compute.d6(1);
                r = new Report(2395);
                r.addDesc(entity);
                r.add(roll);
                r.subject = entity.getId();
                addReport(r);
                if (roll == 6) {
                    nextHex.removeTerrain(Terrains.MAGMA);
                    nextHex.addTerrain(Terrains.getTerrainFactory()
                                               .createTerrain(Terrains.MAGMA, 2));
                    addPacket(createHexChangePacket(curPos, game.getBoard().getHex(curPos)));
                    for (Entity en : game.getEntitiesVector(curPos)) {
                        if (en != entity) {
                            process(server.new MagmaDamage(en, false), game);
                        }
                    }
                }
            }

            // check for entering liquid magma
            if ((nextHex.terrainLevel(Terrains.MAGMA) == 2)
                    && (nextElevation == 0)) {
                process(server.new MagmaDamage(entity, false), game);
            }

            // is the next hex a swamp?
            PilotingRollData rollTarget = entity.checkBogDown(
                    step,
                    moveType,
                    nextHex,
                    curPos,
                    nextPos,
                    step.getElevation(),
                    Compute.canMoveOnPavement(game, curPos, nextPos, step));
            if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                // Taharqa: According to TacOps, you automatically stick if you
                // are skidding, (pg. 63)
                // if (0 < doSkillCheckWhileMoving(entity, curPos, nextPos,
                // rollTarget, false)) {
                entity.setStuck(true);
                r = new Report(2081);
                r.subject = entity.getId();
                r.add(entity.getDisplayName(), true);
                addReport(r);
                // check for quicksand
                process(server.new SwampToQuicksand(nextPos), game);
                // check for accidental stacking violation
                Entity violation = Compute.stackingViolation(game,
                        entity.getId(), curPos);
                if (violation != null) {
                    // target gets displaced, because of low elevation
                    Coords targetDest = Compute.getValidDisplacement(game,
                                                                     entity.getId(), curPos, direction);
                    process(server.new EntityDisplacement(violation, curPos, targetDest,
                            new PilotingRollData(violation.getId(), 0, "domino effect")), game);
                    // Update the violating entity's postion on the client.
                    process(server.new EntityUpdate(violation.getId()), game);
                }
                // stay here and stop skidding, see bug 1115608
                break;
                // }
            }

            // Update the position and keep skidding.
            curPos = nextPos;
            curHex = nextHex;
            currentElevation = nextElevation;
            r = new Report(2085);
            r.subject = entity.getId();
            r.indent();
            r.add(curPos.getBoardNum(), true);
            addReport(r);
            
            if (flip && entity instanceof Tank) {
                doVehicleFlipDamage(flipDamage, game);
            }

        } // Handle the next skid hex.

        // If the skidding entity violates stacking,
        // displace targets until it doesn't.
        curPos = entity.getPosition();
        Entity target = Compute.stackingViolation(game, entity.getId(), curPos);
        while (target != null) {
            nextPos = Compute.getValidDisplacement(game, target.getId(),
                                                   target.getPosition(), direction);
            // ASSUMPTION
            // There should always be *somewhere* that
            // the target can go... last skid hex if
            // nothing else is available.
            if (null == nextPos) {
                // But I don't trust the assumption fully.
                // Report the error and try to continue.
                DefaultMmLogger.getInstance().error(getClass(), METHOD_NAME,
                        "The skid of " + entity.getShortName()
                                + " should displace " + target.getShortName()
                                + " in hex " + curPos.getBoardNum()
                                + " but there is nowhere to go.");
                break;
            }
            // indent displacement
            r = new Report(1210, Report.PUBLIC);
            r.indent();
            r.newlines = 0;
            addReport(r);
            process(server.new EntityDisplacement(target, curPos, nextPos, null), game);
            process(server.new EntityDisplacementMinefieldCheck(entity, curPos,
                    nextPos, entity.getElevation()), game);
            target = Compute.stackingViolation(game, entity.getId(), curPos);
        }

        // Mechs suffer damage for every hex skidded.
        // For QuadVees in vehicle mode, apply
        // damage only if flipping.
        boolean mechDamage = entity instanceof Mech
                && !(entity.getMovementMode() == EntityMovementMode.WIGE
                    && entity.getElevation() > 0);
        if (entity instanceof QuadVee && entity.getConversionMode() == QuadVee.CONV_MODE_VEHICLE) {
            mechDamage = flip;
        }
        if (mechDamage) {
            // Calculate one half falling damage times skid length.
            int damage = skidDistance
                         * (int) Math
                    .ceil(Math.round(entity.getWeight() / 10.0) / 2.0);

            // report skid damage
            r = new Report(2090);
            r.subject = entity.getId();
            r.indent();
            r.addDesc(entity);
            r.add(damage);
            addReport(r);

            // standard damage loop
            // All skid damage is to the front.
            while (damage > 0) {
                int cluster = Math.min(5, damage);
                HitData hit = entity.rollHitLocation(ToHitData.HIT_NORMAL,
                                                     ToHitData.SIDE_FRONT);
                hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                process(server.new EntityDamage(entity, hit, cluster), game);
                damage -= cluster;
            }
            addNewLines();
        }

        if (flip && entity instanceof Tank) {
            process(server.new CriticalHitHandler(entity, Entity.NONE,
                    new CriticalSlot(0, Tank.CRIT_CREW_STUNNED), true, 0, false), game);
        } else if (flip && entity instanceof QuadVee && entity.getConversionMode() == QuadVee.CONV_MODE_VEHICLE) {
            // QuadVees don't suffer stunned crew criticals; require PSR to avoid damage instead.
            PilotingRollData prd = entity.getBasePilotingRoll();
            process(server.new PilotFallDamage(entity, 1, prd), game);            
        }

        // Clean up the entity if it has been destroyed.
        if (entity.isDoomed()) {
            entity.setDestroyed(true);
            game.moveToGraveyard(entity.getId());
            addPacket(createRemoveEntityPacket(entity.getId()));

            // The entity's movement is completed.
            removeFromPlay = true;
            return;
        }

        // Let the player know the ordeal is over.
        r = new Report(2095);
        r.subject = entity.getId();
        r.indent();
        addReport(r);

        return;
    }

    /**
     * Sets the values that change while processing the skid to their initial values.
     * 
     * @param game The server's {@link IGame game} instance.
     */
    void initStartingValues(IGame game) {
        nextPos = start;
        curPos = nextPos;
        curHex = game.getBoard().getHex(start);
        distRemaining = origDistance;
        currentElevation = startElevation;
        skidDistance = 0;
    }
    
    /**
     * Updates current position and finds the next position and elevation, and subtracts the cost of entering
     * the next hex from the remaining distance. If the next hex is not on the map, either removes the
     * entity or stops the skid, depending on game options.
     * 
     * @param game The server's {@link IGame game} instance
     * @return     {@code true} if the position was successfully updated, {@code false} if the next hex
     *             in the skid would carry the unit off the map
     */
    boolean updatePosition(IGame game) {
        nextPos = curPos.translated(direction);
        // Is the next hex off the board?
        if (!game.getBoard().contains(nextPos)) {
            checkSkidOffMap(game);
            // Whether we can leave the map or not, the skid is finished
            return false;
        }

        nextHex = game.getBoard().getHex(nextPos);
        distRemaining -= nextHex.movementCost(entity) + 1;
        calcNextElevation();
        return true;
    }
    
    /**
     * If the game options allow eliminating units by pushing them off the board, removes the entity from
     * play along with any that it might be carrying. Otherwise stops the skid at the edge of the map.
     * 
     * @param game The server's {@link IGame game} instance
     */
    void checkSkidOffMap(IGame game) {
        Report r;
        // Can the entity skid off the map?
        if (game.getOptions().booleanOption(OptionsConstants.BASE_PUSH_OFF_BOARD)) {
            // Yup. One dead entity.
            game.removeEntity(entity.getId(), IEntityRemovalConditions.REMOVE_PUSHED);
            addPacket(createRemoveEntityPacket(entity.getId(), IEntityRemovalConditions.REMOVE_PUSHED));
            r = new Report(2030, Report.PUBLIC);
            r.addDesc(entity);
            addReport(r);

            for (Entity e : entity.getLoadedUnits()) {
                game.removeEntity(e.getId(),
                                  IEntityRemovalConditions.REMOVE_PUSHED);
                addPacket(createRemoveEntityPacket(e.getId(),
                                              IEntityRemovalConditions.REMOVE_PUSHED));
            }
            Entity swarmer = game
                    .getEntity(entity.getSwarmAttackerId());
            if (swarmer != null) {
                if (!swarmer.isDone()) {
                    game.removeTurnFor(swarmer);
                    swarmer.setDone(true);
                    addPacket(createTurnVectorPacket(game));
                }
                game.removeEntity(swarmer.getId(),
                                  IEntityRemovalConditions.REMOVE_PUSHED);
                addPacket(createRemoveEntityPacket(swarmer.getId(),
                                              IEntityRemovalConditions.REMOVE_PUSHED));
            }
            // The entity's movement is completed.
            removeFromPlay = true;
        } else {
            // Nope. Update the report.
            r = new Report(2035);
            r.subject = entity.getId();
            r.indent();
            addReport(r);
        }
    }
    
    /**
     * Determines the {@link Entity}'s logical altitude in the next hex based on terrain. A drop in
     * altitude may mean a fall, and a rise in altitude usually means a crash into terrain.
     * 
     * @param nextHex The next hex the Entity will skid/sideslip into.
     */
    void calcNextElevation() {
        // By default, the unit is going to fall to the floor of the next
        // hex
        curAltitude = currentElevation + curHex.getLevel();
        nextAltitude = nextHex.floor();

        // but VTOL keep altitude
        if (entity.getMovementMode() == EntityMovementMode.VTOL) {
            nextAltitude = Math.max(nextAltitude, curAltitude);
        } else if (entity.getMovementMode() == EntityMovementMode.WIGE
                && currentElevation > 0 && nextAltitude < curAltitude) {
            // Airborne WiGEs drop to one level above the surface
            nextAltitude++;
        } else {
            // Is there a building to "catch" the unit?
            if (nextHex.containsTerrain(Terrains.BLDG_ELEV)) {
                // unit will land on the roof, if at a higher level,
                // otherwise it will skid through the wall onto the same
                // floor.
                // don't change this if the building starts at an elevation
                // higher than the unit
                // (e.g. the building is on a hill). Otherwise, we skid into
                // solid earth.
                if (curAltitude >= nextHex.floor()) {
                    nextAltitude = Math
                            .min(curAltitude,
                                 nextHex.getLevel()
                                 + nextHex
                                         .terrainLevel(Terrains.BLDG_ELEV));
                }
            }
            // Is there a bridge to "catch" the unit?
            if (nextHex.containsTerrain(Terrains.BRIDGE)) {
                // unit will land on the bridge, if at a higher level,
                // and the bridge exits towards the current hex,
                // otherwise the bridge has no effect
                int exitDir = (direction + 3) % 6;
                exitDir = 1 << exitDir;
                if ((nextHex.getTerrain(Terrains.BRIDGE).getExits() & exitDir) == exitDir) {
                    nextAltitude = Math
                            .min(curAltitude,
                                 Math.max(
                                         nextAltitude,
                                         nextHex.getLevel()
                                         + nextHex
                                                 .terrainLevel(Terrains.BRIDGE_ELEV)));
                }
            }
            if ((nextAltitude <= nextHex.surface())
                && (curAltitude >= curHex.surface())) {
                // Hovercraft can "skid" over water.
                // all units can skid over ice.
                if ((entity instanceof Tank)
                        && (entity.getMovementMode() == EntityMovementMode.HOVER)
                        && nextHex.containsTerrain(Terrains.WATER)) {
                    nextAltitude = nextHex.surface();
                } else {
                    if (nextHex.containsTerrain(Terrains.ICE)) {
                        nextAltitude = nextHex.surface();
                    }
                }
            }
        }
        // The elevation the skidding unit will occupy in next hex
        nextElevation = nextAltitude - nextHex.surface();
    }

    /**
     * An {@link Entity} that skids into a hex with a surface higher than what it occupied in the previous
     * hex will usually crash into terrain. Exceptions are WiGE and similar movement, which allows it
     * to rise one elevation level. Airborne units can crash into terrain even if flying above the surface
     * of the hex, if the terrain is woods or jungle.
     * 
     * @param game The server's {@link IGame game} instance.
     * 
     * @return Whether the {@link Entity} crashes into the terrain of the next hex.
     */
    boolean checkTerrainCollision(IGame game) {
        if ((entity.getMovementMode() == EntityMovementMode.VTOL)
                && (nextHex.containsTerrain(Terrains.WOODS)
                        || nextHex.containsTerrain(Terrains.JUNGLE))
                && (nextAltitude < nextHex.ceiling())) {
            return true;
        }

        // Walls and gun emplacements don't have roofs to land on
        if (nextHex.containsTerrain(Terrains.BLDG_ELEV)) {
            Building bldg = game.getBoard().getBuildingAt(nextPos);

            if ((bldg.getType() == Building.WALL) 
                    || (bldg.getType() == Building.GUN_EMPLACEMENT)) {
                return true;
            }
        }

        // however WIGE can gain 1 level to avoid crashing into the terrain.
        if ((entity.getMovementMode() == EntityMovementMode.WIGE) && (currentElevation > 0)) {
            if (curAltitude == nextHex.floor()) {
                nextElevation = 1;
                return false;
            } else if ((entity.hasETypeFlag(Entity.ETYPE_LAND_AIR_MECH))
                    && (curAltitude + 1 == nextHex.floor())) {
                // LAMs in airmech mode skid across terrain that is two levels higher rather than crashing,
                // Reset the skid distance for skid damage calculations.
                nextElevation = 0;
                skidDistance = 0;
                Report r = new Report(2102);
                r.subject = entity.getId();
                r.indent();
                addReport(r);
                return false;
            }
        }
        // None of the exceptions apply; compare surface heights
        return curAltitude < nextAltitude;
    }
    
    /**
     * Handles collision with terrain, including buildings.
     *
     * @param game
     * @param nextHex
     */
    void processTerrainCollision(IGame game) {
        Report r;
        if (nextHex.containsTerrain(Terrains.BLDG_ELEV)) {
            Building bldg = game.getBoard().getBuildingAt(nextPos);

            // If you crash into a wall you want to stop in the hex
            // before the wall not in the wall
            // Like a building.
            if (bldg.getType() == Building.WALL) {
                r = new Report(2047);
            } else if (bldg.getBldgClass() == Building.GUN_EMPLACEMENT) {
                r = new Report(2049);
            } else {
                r = new Report(2045);
            }

        } else {
            r = new Report(2045);
        }

        r.subject = entity.getId();
        r.indent();
        r.add(nextPos.getBoardNum(), true);
        addReport(r);

        if ((entity.getMovementMode() == EntityMovementMode.WIGE)
                || (entity.getMovementMode() == EntityMovementMode.VTOL)) {
            int hitSide = (step.getFacing() - direction) + 6;
            hitSide %= 6;
            int table = 0;
            switch (hitSide) {// quite hackish...I think it ought to
                // work, though.
                case 0:// can this happen?
                    table = ToHitData.SIDE_FRONT;
                    break;
                case 1:
                case 2:
                    table = ToHitData.SIDE_LEFT;
                    break;
                case 3:
                    table = ToHitData.SIDE_REAR;
                    break;
                case 4:
                case 5:
                    table = ToHitData.SIDE_RIGHT;
                    break;
            }
            currentElevation = nextElevation;
            if (entity instanceof Tank) {
                process(server.new AirborneVehicleCrash((Tank) entity, false, true,
                        distRemaining, curPos, currentElevation, table), game);
            }

            if ((nextHex.containsTerrain(Terrains.WATER) && !nextHex
                    .containsTerrain(Terrains.ICE))
                    || nextHex.containsTerrain(Terrains.WOODS)
                    || nextHex.containsTerrain(Terrains.JUNGLE)) {
                process(server.new EntityDestruction(entity,
                        "could not land in crash site"), game);
            } else if (currentElevation < nextHex
                    .terrainLevel(Terrains.BLDG_ELEV)) {
                Building bldg = game.getBoard().getBuildingAt(nextPos);

                // If you crash into a wall you want to stop in the hex
                // before the wall not in the wall
                // Like a building.
                if (bldg.getType() == Building.WALL) {
                    process(server.new EntityDestruction(entity,
                            "crashed into a wall"), game);
                    return;
                }
                if (bldg.getBldgClass() == Building.GUN_EMPLACEMENT) {
                    process(server.new EntityDestruction(entity,
                            "crashed into a gun emplacement"), game);
                    return;
                }

                process(server.new EntityDestruction(entity, "crashed into building"), game);
            } else {
                entity.setPosition(nextPos);
                entity.setElevation(0);
                process(server.new EntityDisplacementMinefieldCheck(entity,
                        curPos, nextPos, nextElevation), game);
            }
            curPos = nextPos;
            return;

        }
        // skidding into higher terrain does weight/20
        // damage in 5pt clusters to front.
        int damage = ((int) entity.getWeight() + 19) / 20;
        while (damage > 0) {
            int table = ToHitData.HIT_NORMAL;
            int side = entity.sideTable(nextPos);
            if (entity instanceof Protomech) {
                table = ToHitData.HIT_SPECIAL_PROTO;
            }
            process(server.new EntityDamage(entity,
                                   entity.rollHitLocation(table, side),
                                   Math.min(5, damage)), game);
            damage -= 5;
        }
    }

    /**
     * Checks whether there is a grounded dropship in the next hex. If the skidding {@link Entity} is
     * airborne and sideslipping, also checks whether it is low enough to collide.
     * 
     * @param game The server's {@link IGame game} instance}
     * @return     The Dropship to collide with, if any, or {@code null} if there is not one.
     */
    @Nullable Entity checkDropshipCollision(IGame game) {
        for (Entity en : game.getEntitiesVector(nextPos)) {
            if ((en.hasETypeFlag(Entity.ETYPE_DROPSHIP)) && !en.isAirborne()
                    && (nextAltitude <= (en.relHeight()))) {
                return en;
            }
        }
        return null;
    }

    /**
     * Executes a charge attack against a grounded dropship.
     * 
     * @param crashDropship The grounded dropship
     * @param game          The server's {@link IGame game} instance
     * @return              Whether the skid can continue past the dropship 
     */
    boolean processDropshipCollision(Entity crashDropship, IGame game) {
        Report r = new Report(2050);
        r.subject = entity.getId();
        r.indent();
        r.add(crashDropship.getShortName(), true);
        r.add(nextPos.getBoardNum(), true);
        addReport(r);
        ChargeAttackAction caa = new ChargeAttackAction(entity.getId(),
                crashDropship.getTargetType(),
                crashDropship.getTargetId(),
                crashDropship.getPosition());
        ToHitData toHit = caa.toHit(game, true);
        process(server.new ChargeDamage(entity, crashDropship, toHit, direction), game);
        if ((entity.getMovementMode() == EntityMovementMode.WIGE)
                || (entity.getMovementMode() == EntityMovementMode.VTOL)) {
            int hitSide = (step.getFacing() - direction) + 6;
            hitSide %= 6;
            int table = 0;
            switch (hitSide) {// quite hackish...I think it ought to
                // work, though.
                case 0:// can this happen?
                    table = ToHitData.SIDE_FRONT;
                    break;
                case 1:
                case 2:
                    table = ToHitData.SIDE_LEFT;
                    break;
                case 3:
                    table = ToHitData.SIDE_REAR;
                    break;
                case 4:
                case 5:
                    table = ToHitData.SIDE_RIGHT;
                    break;
            }
            currentElevation = nextElevation;
            process(server.new AirborneVehicleCrash((Tank) entity, false, true,
                    distRemaining, curPos, currentElevation, table), game);
            return false;
        }
        return crashDropship.isDoomed() || crashDropship.isDestroyed() || game.isOutOfGame(crashDropship);
    }
    
    /**
     * Checks for drop in elevation greater than the maximum change, and if so processes accidental fall
     * from above and stops the skid.
     * 
     * @param game The server's {@link IGame game} instance
     * @return     Whether an accidental fall from above occurred.
     */
    boolean fallOffCliff(IGame game) {
        // WiGEs just descend
        if ((entity.getMovementMode() == EntityMovementMode.WIGE)
                && (curAltitude > curHex.ceiling())) {
            return false;
        }
        if (curAltitude > (nextAltitude + entity.getMaxElevationChange())) {
            process(server.new EntityFallIntoHex(entity, entity.getElevation(),
                    curPos, nextPos, entity.getBasePilotingRoll(moveType), true), game);
            process(server.new EntityDisplacementMinefieldCheck(entity,
                    curPos, nextPos, nextElevation), game);
            // Stay in the current hex and stop skidding.
            return true;
        }
        return false;
    }
    
    /**
     * Checks for any entities or buildings in the next hex and processes any resulting charge attacks.
     * 
     * @param game The server's {@link IGame game} instance
     * @return     Whether a charge attack stops the skid
     */
    boolean doAccidentalCharge(IGame game) {
        Report r;
        // Get any building in the hex.
        Building bldg = null;
        if (nextElevation < nextHex.terrainLevel(Terrains.BLDG_ELEV)) {
            // We will only run into the building if its at a higher level,
            // otherwise we skid over the roof
            bldg = game.getBoard().getBuildingAt(nextPos);
        }
        
        boolean bldgSuffered = false;
        boolean stopTheSkid = false;
        // Does the next hex contain an entities?
        // ASSUMPTION: hurt EVERYONE in the hex.
        
        Iterator<Entity> targets = game.getEntities(nextPos);
        if (targets.hasNext()) {
            ArrayList<Entity> avoidedChargeUnits = new ArrayList<Entity>();
            boolean skidChargeHit = false;
            while (targets.hasNext()) {
                Entity target = targets.next();

                if ((target.getElevation() > (nextElevation + entity
                        .getHeight()))
                    || (target.relHeight() < nextElevation)) {
                    // target is not in the way
                    continue;
                }

                if (canAvoidSkid(target, game)) {
                    if (!target.hasETypeFlag(Entity.ETYPE_INFANTRY)
                            && !target.hasETypeFlag(Entity.ETYPE_PROTOMECH)) {
                        game.removeTurnFor(target);
                        avoidedChargeUnits.add(target);
                    }
                    continue;
                }

                // Mechs and vehicles get charged,
                // but need to make a to-hit roll
                if ((target instanceof Mech) || (target instanceof Tank)
                    || (target instanceof Aero)) {
                    ChargeAttackAction caa = new ChargeAttackAction(
                            entity.getId(), target.getTargetType(),
                            target.getTargetId(), target.getPosition());
                    ToHitData toHit = caa.toHit(game, true);

                    // roll
                    int roll = Compute.d6(2);
                    // Update report.
                    r = new Report(2050);
                    r.subject = entity.getId();
                    r.indent();
                    r.add(target.getShortName(), true);
                    r.add(nextPos.getBoardNum(), true);
                    r.newlines = 0;
                    addReport(r);
                    if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
                        roll = -12;
                        r = new Report(2055);
                        r.subject = entity.getId();
                        r.add(toHit.getDesc());
                        r.newlines = 0;
                        addReport(r);
                    } else if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
                        r = new Report(2060);
                        r.subject = entity.getId();
                        r.add(toHit.getDesc());
                        r.newlines = 0;
                        addReport(r);
                        roll = Integer.MAX_VALUE;
                    } else {
                        // report the roll
                        r = new Report(2065);
                        r.subject = entity.getId();
                        r.add(toHit.getValue());
                        r.add(roll);
                        r.newlines = 0;
                        addReport(r);
                    }

                    // Resolve a charge against the target.
                    // ASSUMPTION: buildings block damage for
                    // *EACH* entity charged.
                    if (roll < toHit.getValue()) {
                        r = new Report(2070);
                        r.subject = entity.getId();
                        addReport(r);
                    } else {
                        // Resolve the charge.
                        process(server.new ChargeDamage(entity, target, toHit,
                                            direction), game);
                        // HACK: set the entity's location
                        // to the original hex again, for the other targets
                        if (targets.hasNext()) {
                            entity.setPosition(curPos);
                        }
                        bldgSuffered = true;
                        skidChargeHit = true;
                        // The skid ends here if the target lives.
                        if (!target.isDoomed() && !target.isDestroyed()
                            && !game.isOutOfGame(target)) {
                            stopTheSkid = true;
                        }
                    }

                    // if we don't do this here,
                    // we can have a mech without a leg
                    // standing on the field and moving
                    // as if it still had his leg after
                    // getting skid-charged.
                    if (!target.isDone()) {
                        process(server.new PilotingSkillRoll(target), game);
                        game.resetPSRs(target);
                        target.applyDamage();
                        addNewLines();
                    }

                }

                // Resolve "move-through" damage on infantry.
                // Infantry inside of a building don't get a
                // move-through, but suffer "bleed through"
                // from the building.
                else if ((target instanceof Infantry) && (bldg != null)) {
                    // Update report.
                    r = new Report(2075);
                    r.subject = entity.getId();
                    r.indent();
                    r.add(target.getShortName(), true);
                    r.add(nextPos.getBoardNum(), true);
                    r.newlines = 0;
                    addReport(r);

                    // Infantry don't have different
                    // tables for punches and kicks
                    HitData hit = target.rollHitLocation(
                            ToHitData.HIT_NORMAL,
                            Compute.targetSideTable(entity, target));
                    hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                    // Damage equals tonnage, divided by 5.
                    // ASSUMPTION: damage is applied in one hit.
                    process(server.new EntityDamage(target, hit,
                                           (int) Math.round(entity.getWeight() / 5)), game);
                    addNewLines();
                }

                // Has the target been destroyed?
                if (target.isDoomed()) {

                    // Has the target taken a turn?
                    if (!target.isDone()) {

                        // Dead entities don't take turns.
                        game.removeTurnFor(target);
                        addPacket(createTurnVectorPacket(game));

                    } // End target-still-to-move

                    // Clean out the entity.
                    target.setDestroyed(true);
                    game.moveToGraveyard(target.getId());
                    addPacket(createRemoveEntityPacket(target.getId()));
                }

                // Update the target's position,
                // unless it is off the game map.
                if (!game.isOutOfGame(target)) {
                    process(server.new EntityUpdate(target.getId()), game);
                }

            } // Check the next entity in the hex.

            if (skidChargeHit) {
                // HACK: set the entities position to that
                // hex's coords, because we had to move the entity
                // back earlier for the other targets
                entity.setPosition(nextPos);
            }
            for (Entity e : avoidedChargeUnits) {
                GameTurn newTurn = new GameTurn.SpecificEntityTurn(e
                        .getOwner().getId(), e.getId());
                // Prevents adding extra turns for multi-turns
                newTurn.setMultiTurn(true);
                game.insertNextTurn(newTurn);
                addPacket(createTurnVectorPacket(game));
            }
        }
        // Handle the building in the hex.
        if (bldg != null) {

            // Report that the entity has entered the bldg.
            r = new Report(2080);
            r.subject = entity.getId();
            r.indent();
            r.add(bldg.getName());
            r.add(nextPos.getBoardNum(), true);
            addReport(r);

            // If the building hasn't already suffered
            // damage, then apply charge damage to the
            // building and displace the entity inside.
            // ASSUMPTION: you don't charge the building
            // if Tanks or Mechs were charged.
            int chargeDamage = ChargeAttackAction.getDamageFor(entity, game
                    .getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_CHARGE_DAMAGE),
                    entity.delta_distance);
            if (!bldgSuffered) {
                RuleHandler handler = server.new BuildingDamage(bldg, chargeDamage, nextPos);
                process(handler, game);
                for (Report report : handler.getReports()) {
                    report.subject = entity.getId();
                }

                // Apply damage to the attacker.
                int toAttacker = ChargeAttackAction.getDamageTakenBy(
                        entity, bldg, nextPos);
                HitData hit = entity.rollHitLocation(ToHitData.HIT_NORMAL,
                                                     entity.sideTable(nextPos));
                hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                process(server.new EntityDamage(entity, hit, toAttacker), game);
                addNewLines();

                entity.setPosition(nextPos);
                entity.setElevation(nextElevation);
                process(server.new EntityDisplacementMinefieldCheck(entity,
                        curPos, nextPos, nextElevation), game);
                curPos = nextPos;
            } // End buildings-suffer-too

            // Any infantry in the building take damage
            // equal to the building being charged.
            // ASSUMPTION: infantry take no damage from the
            // building absorbing damage from
            // Tanks and Mechs being charged.
            process(server.new BuildingDamageToInfantry(bldg, chargeDamage, nextPos), game);

            // If a building still stands, then end the skid,
            // and add it to the list of affected buildings.
            if (bldg.getCurrentCF(nextPos) > 0) {
                stopTheSkid = true;
                Vector<Report> phaseReports = new Vector<>();
                boolean basement = bldg.rollBasement(nextPos, game.getBoard(),
                        phaseReports);
                addReport(phaseReports);
                if (basement) {
                    addPacket(createHexChangePacket(nextPos, game.getBoard().getHex(nextPos)));
                    Vector<Building> buildings = new Vector<Building>();
                    buildings.add(bldg);
                    addPacket(createUpdateBuildingPacket(buildings));
                }
                process(server.new BuildingCollapseDuringMovement(entity, bldg, nextPos, true), game);
            } else {
                // otherwise it collapses immediately on our head
                process(server.new BuildingCollapseCheck(bldg, game.getPositionMap(),
                        nextPos, true), game);
            }

        } // End handle-building.
        return stopTheSkid;
    }
    
    /**
     * Checks whether an entity in the target hex can avoid a collision with a skidding unit.
     * Infantry can automatically dodge, protomechs can dodge if it would not create a stacking violation,
     * and large support vehicles can never dodge. All other units can dodge if they make a piloting/
     * driving check.
     *  
     * @param target The potential target of an accidental charge
     * @param game   The server's {@link IGame game} instance
     * @return       Whether the target can avoid the skidding unit.
     */
    boolean canAvoidSkid(Entity target, IGame game) {
        Report r;
        if (!target.isDone()) {
            if (target.hasETypeFlag(Entity.ETYPE_INFANTRY)) {
                r = new Report(2420);
                r.subject = target.getId();
                r.addDesc(target);
                addReport(r);
                return true;
            } else if (target.hasETypeFlag(Entity.ETYPE_PROTOMECH)) {
                if (target != Compute.stackingViolation(game,
                        entity, nextPos, null)) {
                    r = new Report(2420);
                    r.subject = target.getId();
                    r.addDesc(target);
                    addReport(r);
                    return true;
                }
            } else if (!target.hasETypeFlag(Entity.ETYPE_LARGE_SUPPORT_TANK)) {
                PilotingRollData psr = target.getBasePilotingRoll();
                psr.addModifier(0, "avoiding collision");
                int roll = Compute.d6(2);
                r = new Report(2425);
                r.subject = target.getId();
                r.addDesc(target);
                r.add(psr.getValue());
                r.add(psr.getDesc());
                r.add(roll);
                addReport(r);
                if (roll >= psr.getValue()) {
                    return true;
                    // TODO: the charge should really be suspended
                    // and resumed after the target moved.
                }
            }
        }
        return false;
    }

    void doVehicleFlipDamage(int damage, IGame game) {
        HitData hit;
        boolean startRight = direction < 3;
        int flipCount = skidDistance - 1;

        int index = flipCount % 4;
        // If there is no turret, we do side-side-bottom
        if (((Tank)entity).hasNoTurret()) {
            index = flipCount % 3;
            if (index > 0) {
                index++;
            }
        }
        switch (index) {
        case 0:
            hit = new HitData(startRight? Tank.LOC_RIGHT : Tank.LOC_LEFT);
            break;
        case 1:
            hit = new HitData(Tank.LOC_TURRET);
        case 2:
            hit = new HitData(startRight? Tank.LOC_LEFT : Tank.LOC_RIGHT);
            break;
        default:
            hit = null; //Motive damage instead
        }
        if (hit != null) {
            hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
            process(server.new EntityDamage(entity, hit, damage), game);
            // If the vehicle has two turrets, they both take full damage.
            if (hit.getLocation() == Tank.LOC_TURRET
                    && !(((Tank)entity).hasNoDualTurret())) {
                hit = new HitData(Tank.LOC_TURRET_2);
                hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                process(server.new EntityDamage(entity, hit, damage), game);
            }
        } else {
            process(server.new VehicleMotiveDamage((Tank)entity, 1), game);
        }
    }

    /* Getters for unit tests */
    
    Coords getNextPos() {
        return nextPos;
    }

    Coords getCurPos() {
        return curPos;
    }

    IHex getCurHex() {
        return curHex;
    }
    
    IHex getNextHex() {
        return nextHex;
    }

    int getDistRemaining() {
        return distRemaining;
    }

    int getCurrentElevation() {
        return currentElevation;
    }
    
    int getNextElevation() {
        return nextElevation;
    }

    int getCurAltitude() {
        return curAltitude;
    }

    int getNextAltitude() {
        return nextAltitude;
    }

    int getSkidDistance() {
        return skidDistance;
    }

}
