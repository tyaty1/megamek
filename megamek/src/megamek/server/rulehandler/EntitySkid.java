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
import megamek.common.Dropship;
import megamek.common.Entity;
import megamek.common.EntityMovementMode;
import megamek.common.EntityMovementType;
import megamek.common.GameTurn;
import megamek.common.HitData;
import megamek.common.IEntityRemovalConditions;
import megamek.common.IGame;
import megamek.common.IHex;
import megamek.common.Infantry;
import megamek.common.LandAirMech;
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
import megamek.common.VTOL;
import megamek.common.actions.ChargeAttackAction;
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
    final private int elevation;
    final private int direction;
    final private int distance;
    final private MoveStep step;
    final private EntityMovementType moveType;
    final private boolean flip;
    
    /**
     * Indicates whether the {@link Entity Entity} is removed from the game as a result of resolving the skid
     */
    private boolean removeFromGame = false;

    /**
     * @param entity    the unit which should skid
     * @param start     the coordinates of the hex the unit was in prior to skidding
     * @param elevation the elevation of the unit
     * @param direction the direction of the skid
     * @param distance  the number of hexes skidded
     * @param step      the MoveStep which caused the skid
     * @return true if the entity was removed from play
     */
    public EntitySkid(Entity entity, Coords start, int elevation,
            int direction, int distance, MoveStep step,
            EntityMovementType moveType) {
        this(entity, start, elevation, direction, distance,
                step, moveType, false);
    }
    
    /**
     * @param entity    the unit which should skid
     * @param start     the coordinates of the hex the unit was in prior to skidding
     * @param elevation the elevation of the unit
     * @param direction the direction of the skid
     * @param distance  the number of hexes skidded
     * @param step      the MoveStep which caused the skid
     * @param flip      whether the skid resulted from a failure maneuver result of major skid
     * @return true if the entity was removed from play
     */
    public EntitySkid(Entity entity, Coords start, int elevation,
            int direction, int distance, MoveStep step,
            EntityMovementType moveType, boolean flip) {
        super(entity);
        this.start = start;
        this.elevation = elevation;
        this.direction = direction;
        this.distance = distance;
        this.step = step;
        this.moveType = moveType;
        this.flip = flip;
    }
    
    /**
     * After the skid is resolved, this indicates whether the skid results in the {@link Entity Entity} 
     * being removed from the game, either by destruction or by leaving the board.
     */
    public boolean isRemovedFromGame() {
        return removeFromGame;
    }

    private Coords nextPos;
    private Coords curPos;
    private IHex curHex;
    int skidDistance;
    
    @Override
    public void resolve(IGame game) {
        final String METHOD_NAME = "resolve(IGame)"; //$NON-NLS-1$
        nextPos = start;
        curPos = nextPos;
        curHex = game.getBoard().getHex(start);
        Report r;
        skidDistance = 0; // actual distance moved
        // Flipping vehicles take tonnage/10 points of damage for every hex they enter.
        int flipDamage = (int)Math.ceil(entity.getWeight() / 10.0);
        while (!entity.isDoomed() && (distance > 0)) {
            nextPos = curPos.translated(direction);
            // Is the next hex off the board?
            if (!game.getBoard().contains(nextPos)) {

                // Can the entity skid off the map?
                if (game.getOptions().booleanOption(OptionsConstants.BASE_PUSH_OFF_BOARD)) {
                    // Yup. One dead entity.
                    game.removeEntity(entity.getId(),
                                      IEntityRemovalConditions.REMOVE_PUSHED);
                    addPacket(createRemoveEntityPacket(entity.getId(),
                                                  IEntityRemovalConditions.REMOVE_PUSHED));
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
                    removeFromGame = true;
                    return;

                }
                // Nope. Update the report.
                r = new Report(2035);
                r.subject = entity.getId();
                r.indent();
                addReport(r);
                // Stay in the current hex and stop skidding.
                break;
            }

            IHex nextHex = game.getBoard().getHex(nextPos);
            distance -= nextHex.movementCost(entity) + 1;
            // By default, the unit is going to fall to the floor of the next
            // hex
            int curAltitude = elevation + curHex.getLevel();
            int nextAltitude = nextHex.floor();

            // but VTOL keep altitude
            if (entity.getMovementMode() == EntityMovementMode.VTOL) {
                nextAltitude = Math.max(nextAltitude, curAltitude);
            } else if (entity.getMovementMode() == EntityMovementMode.WIGE
                    && elevation > 0 && nextAltitude < curAltitude) {
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
            int nextElevation = nextAltitude - nextHex.surface();

            boolean crashedIntoTerrain = curAltitude < nextAltitude;
            if (entity.getMovementMode() == EntityMovementMode.VTOL) {
                if ((nextElevation == 0)
                    || ((nextElevation == 1) && (nextHex
                                                         .containsTerrain(Terrains.WOODS) || nextHex
                                                         .containsTerrain(Terrains.JUNGLE)))) {
                    crashedIntoTerrain = true;
                }
            }

            if (nextHex.containsTerrain(Terrains.BLDG_ELEV)) {
                Building bldg = game.getBoard().getBuildingAt(nextPos);

                if (bldg.getType() == Building.WALL) {
                    crashedIntoTerrain = true;
                }

                if (bldg.getBldgClass() == Building.GUN_EMPLACEMENT) {
                    crashedIntoTerrain = true;
                }
            }

            // however WIGE can gain 1 level to avoid crashing into the terrain.
            if (entity.getMovementMode() == EntityMovementMode.WIGE && (elevation > 0)) {
                if (curAltitude == nextHex.floor()) {
                    nextElevation = 1;
                    crashedIntoTerrain = false;
                } else if ((entity instanceof LandAirMech) && (curAltitude + 1 == nextHex.floor())) {
                    // LAMs in airmech mode skid across terrain that is two levels higher rather than crashing,
                    // Reset the skid distance for skid damage calculations.
                    nextElevation = 0;
                    skidDistance = 0;
                    crashedIntoTerrain = false;
                    r = new Report(2102);
                    r.subject = entity.getId();
                    r.indent();
                    addReport(r);
                }
            }

            Entity crashDropship = null;
            for (Entity en : game.getEntitiesVector(nextPos)) {
                if ((en instanceof Dropship) && !en.isAirborne()
                    && (nextAltitude <= (en.relHeight()))) {
                    crashDropship = en;
                }
            }

            if (crashedIntoTerrain) {

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
                    elevation = nextElevation;
                    if (entity instanceof Tank) {
                        addReport(crashVTOLorWiGE((Tank) entity, false, true,
                                distance, curPos, elevation, table));
                    }

                    if ((nextHex.containsTerrain(Terrains.WATER) && !nextHex
                            .containsTerrain(Terrains.ICE))
                            || nextHex.containsTerrain(Terrains.WOODS)
                            || nextHex.containsTerrain(Terrains.JUNGLE)) {
                        addReport(destroyEntity(entity,
                                "could not land in crash site"));
                    } else if (elevation < nextHex
                            .terrainLevel(Terrains.BLDG_ELEV)) {
                        Building bldg = game.getBoard().getBuildingAt(nextPos);

                        // If you crash into a wall you want to stop in the hex
                        // before the wall not in the wall
                        // Like a building.
                        if (bldg.getType() == Building.WALL) {
                            addReport(destroyEntity(entity,
                                    "crashed into a wall"));
                            break;
                        }
                        if (bldg.getBldgClass() == Building.GUN_EMPLACEMENT) {
                            addReport(destroyEntity(entity,
                                    "crashed into a gun emplacement"));
                            break;
                        }

                        addReport(destroyEntity(entity, "crashed into building"));
                    } else {
                        entity.setPosition(nextPos);
                        entity.setElevation(0);
                        addReport(doEntityDisplacementMinefieldCheck(entity,
                                curPos, nextPos, nextElevation));
                    }
                    curPos = nextPos;
                    break;

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
                    addReport(damageEntity(entity,
                                           entity.rollHitLocation(table, side),
                                           Math.min(5, damage)));
                    damage -= 5;
                }
                // Stay in the current hex and stop skidding.
                break;
            }

            // did we hit a dropship. Oww!
            // Taharqa: The rules on how to handle this are completely missing,
            // so I am assuming
            // we assign damage as per an accidental charge, but do not displace
            // the dropship and
            // end the skid
            else if (null != crashDropship) {
                r = new Report(2050);
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
                resolveChargeDamage(entity, crashDropship, toHit, direction);
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
                    elevation = nextElevation;
                    addReport(crashVTOLorWiGE((VTOL) entity, false, true,
                            distance, curPos, elevation, table));
                    break;
                }
                if (!crashDropship.isDoomed() && !crashDropship.isDestroyed()
                    && !game.isOutOfGame(crashDropship)) {
                    break;
                }
            }

            // Have skidding units suffer falls (off a cliff).
            else if (curAltitude > (nextAltitude + entity
                    .getMaxElevationChange())
                    && !(entity.getMovementMode() == EntityMovementMode.WIGE
                            && elevation > curHex.ceiling())) {
                addReport(doEntityFallsInto(entity, entity.getElevation(),
                        curPos, nextPos,
                        entity.getBasePilotingRoll(moveType), true));
                addReport(doEntityDisplacementMinefieldCheck(entity,
                        curPos, nextPos, nextElevation));
                // Stay in the current hex and stop skidding.
                break;
            }

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

                    // Can the target avoid the skid?
                    if (!target.isDone()) {
                        if (target instanceof Infantry) {
                            r = new Report(2420);
                            r.subject = target.getId();
                            r.addDesc(target);
                            addReport(r);
                            continue;
                        } else if (target instanceof Protomech) {
                            if (target != Compute.stackingViolation(game,
                                    entity, nextPos, null)) {
                                r = new Report(2420);
                                r.subject = target.getId();
                                r.addDesc(target);
                                addReport(r);
                                continue;
                            }
                        } else {
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
                                game.removeTurnFor(target);
                                avoidedChargeUnits.add(target);
                                continue;
                                // TODO: the charge should really be suspended
                                // and resumed after the target moved.
                            }
                        }
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
                            resolveChargeDamage(entity, target, toHit,
                                                direction);
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
                            addReport(resolvePilotingRolls(target));
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
                        addReport(damageEntity(target, hit,
                                               (int) Math.round(entity.getWeight() / 5)));
                        addNewLines();
                    }

                    // Has the target been destroyed?
                    if (target.isDoomed()) {

                        // Has the target taken a turn?
                        if (!target.isDone()) {

                            // Dead entities don't take turns.
                            game.removeTurnFor(target);
                            send(createTurnVectorPacket());

                        } // End target-still-to-move

                        // Clean out the entity.
                        target.setDestroyed(true);
                        game.moveToGraveyard(target.getId());
                        send(createRemoveEntityPacket(target.getId()));
                    }

                    // Update the target's position,
                    // unless it is off the game map.
                    if (!game.isOutOfGame(target)) {
                        entityUpdate(target.getId());
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
                    send(createTurnVectorPacket());
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
                    Vector<Report> reports = damageBuilding(bldg, chargeDamage,
                                                            nextPos);
                    for (Report report : reports) {
                        report.subject = entity.getId();
                    }
                    addReport(reports);

                    // Apply damage to the attacker.
                    int toAttacker = ChargeAttackAction.getDamageTakenBy(
                            entity, bldg, nextPos);
                    HitData hit = entity.rollHitLocation(ToHitData.HIT_NORMAL,
                                                         entity.sideTable(nextPos));
                    hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                    addReport(damageEntity(entity, hit, toAttacker));
                    addNewLines();

                    entity.setPosition(nextPos);
                    entity.setElevation(nextElevation);
                    addReport(doEntityDisplacementMinefieldCheck(entity,
                                                                 curPos, nextPos, nextElevation));
                    curPos = nextPos;
                } // End buildings-suffer-too

                // Any infantry in the building take damage
                // equal to the building being charged.
                // ASSUMPTION: infantry take no damage from the
                // building absorbing damage from
                // Tanks and Mechs being charged.
                addReport(damageInfantryIn(bldg, chargeDamage, nextPos));

                // If a building still stands, then end the skid,
                // and add it to the list of affected buildings.
                if (bldg.getCurrentCF(nextPos) > 0) {
                    stopTheSkid = true;
                    if (bldg.rollBasement(nextPos, game.getBoard(),
                                          vPhaseReport)) {
                        addPacket(createHexChangePacket(nextPos, game.getBoard().getHex(nextPos)));
                        Vector<Building> buildings = new Vector<Building>();
                        buildings.add(bldg);
                        sendChangedBuildings(buildings);
                    }
                    addChild(new Server.BuildingCollapseDuringMovement(entity, bldg, nextPos, true));
                } else {
                    // otherwise it collapses immediately on our head
                    checkForCollapse(bldg, game.getPositionMap(), nextPos,
                                     true, vPhaseReport);
                }

            } // End handle-building.

            // Do we stay in the current hex and stop skidding?
            if (stopTheSkid) {
                break;
            }

            // Update entity position and elevation
            entity.setPosition(nextPos);
            entity.setElevation(nextElevation);
            addReport(doEntityDisplacementMinefieldCheck(entity, curPos,
                                                         nextPos, nextElevation));
            skidDistance++;

            // Check for collapse of any building the entity might be on
            Building roof = game.getBoard().getBuildingAt(nextPos);
            if (roof != null) {
                if (checkForCollapse(roof, game.getPositionMap(), nextPos,
                                     true, vPhaseReport)) {
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
                    addReport(destroyEntity(entity,
                            "skidded into a watery grave", false, true));
                }

                // otherwise, damage is weight/5 in 5pt clusters
                int damage = ((int) entity.getWeight() + 4) / 5;
                while (damage > 0) {
                    addReport(damageEntity(entity, entity.rollHitLocation(
                            ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT),
                            Math.min(5, damage)));
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
                    sendChangedHex(curPos);
                    for (Entity en : game.getEntitiesVector(curPos)) {
                        if (en != entity) {
                            doMagmaDamage(en, false);
                        }
                    }
                }
            }

            // check for entering liquid magma
            if ((nextHex.terrainLevel(Terrains.MAGMA) == 2)
                && (nextElevation == 0)) {
                doMagmaDamage(entity, false);
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
                addReport(checkQuickSand(nextPos));
                // check for accidental stacking violation
                Entity violation = Compute.stackingViolation(game,
                        entity.getId(), curPos);
                if (violation != null) {
                    // target gets displaced, because of low elevation
                    Coords targetDest = Compute.getValidDisplacement(game,
                                                                     entity.getId(), curPos, direction);
                    addReport(doEntityDisplacement(violation, curPos,
                                                   targetDest, new PilotingRollData(violation.getId(),
                                                                                    0, "domino effect")));
                    // Update the violating entity's postion on the client.
                    entityUpdate(violation.getId());
                }
                // stay here and stop skidding, see bug 1115608
                break;
                // }
            }

            // Update the position and keep skidding.
            curPos = nextPos;
            curHex = nextHex;
            elevation = nextElevation;
            r = new Report(2085);
            r.subject = entity.getId();
            r.indent();
            r.add(curPos.getBoardNum(), true);
            addReport(r);
            
            if (flip && entity instanceof Tank) {
                doVehicleFlipDamage((Tank)entity, flipDamage, direction < 3, skidDistance - 1);
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
                logError(METHOD_NAME,
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
            addReport(doEntityDisplacement(target, curPos, nextPos, null));
            addReport(doEntityDisplacementMinefieldCheck(entity, curPos,
                                                         nextPos, entity.getElevation()));
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
                addReport(damageEntity(entity, hit, cluster));
                damage -= cluster;
            }
            addNewLines();
        }

        if (flip && entity instanceof Tank) {
            addChild(new Server.CriticalHitHandler(entity, Entity.NONE,
                    new CriticalSlot(0, Tank.CRIT_CREW_STUNNED), true, 0, false));
        } else if (flip && entity instanceof QuadVee && entity.getConversionMode() == QuadVee.CONV_MODE_VEHICLE) {
            // QuadVees don't suffer stunned crew criticals; require PSR to avoid damage instead.
            PilotingRollData prd = entity.getBasePilotingRoll();
            addChild(Server.PilotFallDamage(entity, 1, prd));            
        }

        // Clean up the entity if it has been destroyed.
        if (entity.isDoomed()) {
            entity.setDestroyed(true);
            game.moveToGraveyard(entity.getId());
            send(createRemoveEntityPacket(entity.getId()));

            // The entity's movement is completed.
            return true;
        }

        // Let the player know the ordeal is over.
        r = new Report(2095);
        r.subject = entity.getId();
        r.indent();
        addReport(r);

        return false;
    }


}
