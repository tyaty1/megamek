/*
 * MegaMek -
 * Copyright (C) 2000,2001,2002,2003,2004,2005 Ben Mazur (bmazur@sev.org)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 */

package megamek.client.bot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.Vector;

import megamek.client.bot.MoveOption.DamageInfo;
import megamek.common.AmmoType;
import megamek.common.BattleArmor;
import megamek.common.Compute;
import megamek.common.Coords;
import megamek.common.Entity;
import megamek.common.EntityMovementType;
import megamek.common.EquipmentType;
import megamek.common.IAimingModes;
import megamek.common.IHex;
import megamek.common.Infantry;
import megamek.common.Mech;
import megamek.common.Minefield;
import megamek.common.MiscType;
import megamek.common.Mounted;
import megamek.common.MovePath;
import megamek.common.MovePath.MoveStepType;
import megamek.common.Protomech;
import megamek.common.TargetRoll;
import megamek.common.Terrains;
import megamek.common.ToHitData;
import megamek.common.WeaponType;
import megamek.common.actions.ChargeAttackAction;
import megamek.common.actions.DfaAttackAction;
import megamek.common.actions.EntityAction;
import megamek.common.actions.TorsoTwistAction;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.containers.PlayerIDandList;
import megamek.common.event.GamePlayerChatEvent;
import megamek.common.logging.DefaultMmLogger;
import megamek.common.logging.MMLogger;
import megamek.common.options.OptionsConstants;

import static megamek.common.logging.LogLevel.*;

public class TestBot extends BotClient {

    private static final MMLogger LOGGER = DefaultMmLogger.getInstance();

    CEntity.Table centities = new CEntity.Table(this);
    private final ChatProcessor chatp = new ChatProcessor();
    protected int ignore;
    boolean debug;
    private int enemies_moved = 0;
    private GALance old_moves = null;

    public TestBot(final String name,
                   final String host,
                   final int port) {
        super(name, host, port);
        ignore = config.getIgnoreLevel();
        debug = config.isDebug();
    }

    @Override
    public void initialize() {
        // removed
    }

    @Override
    public PhysicalOption calculatePhysicalTurn() {
        return PhysicalCalculator.calculatePhysicalTurn(this);
    }

    /**
     * Used by the function calculateMoveTurn to run each entities movement
     * calculation in a separate thread.
     *
     * @author Mike Kiscaden
     */
    public class CalculateEntityMove implements Runnable {

        private final Entity entity;
        private MoveOption[] result;

        CalculateEntityMove(final Entity entity) {
            this.entity = entity;
        }

        public void run() {
            result = calculateMove(entity);
        }

        public Entity getEntity() {
            return entity;
        }

        public MoveOption[] getResult() {
            return result;
        }

    }

    @Override
    public MovePath calculateMoveTurn() {
        final String methodName = "calculateMoveTurn()";

        final long enter = System.currentTimeMillis();
        int initiative = 0;
        MoveOption min = null;

        LOGGER.log(getClass(), methodName, DEBUG, "beginning movement calculations...");

        // first check and that someone else has moved so we don't replan
        final Object[] enemy_array = getEnemyEntities().toArray();
        for (final Object anEnemy_array : enemy_array) {
            if (!((Entity) anEnemy_array).isSelectableThisTurn()) {
                initiative++;
            }
        }
        // if nobody's moved and we have a valid move waiting, use that
        if ((initiative == enemies_moved) && (null != old_moves)) {
            min = old_moves.getResult();
            if ((null == min)
                || !min.isMoveLegal()
                || (min.isPhysical && centities.get(min
                                                            .getPhysicalTargetId()).isPhysicalTarget)) {
                old_moves = null;
                LOGGER.log(getClass(), methodName, DEBUG, "recalculating moves since the old move was invalid");
                return calculateMoveTurn();
            }
        } else {
            enemies_moved = initiative;
            final ArrayList<MoveOption[]> possible = new ArrayList<>();

            for (final Entity entity : game.getEntitiesVector()) {

                // ignore loaded and off-board units
                if ((null == entity.getPosition()) || entity.isOffBoard()) {
                    continue;
                }

                final CEntity cen = centities.get(entity);
                cen.refresh();
                firstPass(cen);
            }

            final Iterator<Entity> i = getEntitiesOwned().iterator();
            boolean short_circuit = false;

            final List<Thread> threads = new ArrayList<>();
            final List<CalculateEntityMove> tasks = new ArrayList<>();
            while (i.hasNext()) {
                final Entity entity = i.next();

                // ignore loaded units
                // (not really necessary unless bot manages to load units)
                if (null == entity.getPosition()) {
                    continue;
                }

                // if we can't move this entity right now, ignore it
                if (!game.getTurn().isValidEntity(entity, game)) {
                    continue;
                }

                final CalculateEntityMove task = new CalculateEntityMove(entity);
                tasks.add(task);
                final Thread worker = new Thread(task);
                worker.setName("Entity:" + entity.getId());
                worker.start();
                threads.add(worker);

            }
            int running;
            synchronized (this) {
                do {
                    running = 0;
                    for (final Thread thread : threads) {
                        if (thread.isAlive()) {
                            running++;
                        }
                    }
                    try {
                        Thread.sleep(5000);
                    } catch (final InterruptedException e1) {
                        LOGGER.log(getClass(), methodName, ERROR, "Interrupted waiting for Bot to move.", e1);
                    } // Technically we should be using wait() but its not
                    // waking up reliably.
                    if (0 < running) {
                        sendChat("Calculating the move for " + running
                                 + " units. ");
                    } else {
                        sendChat("Finalizing move.");
                    }
                } while (0 < running);
            }
            // Threads are done running. Process the results.
            for (final CalculateEntityMove task : tasks) {
                final MoveOption[] result = task.getResult();
                final CEntity cen = centities.get(task.getEntity());
                if (game.getBooleanOption(OptionsConstants.BASE_SKIP_INELIGABLE_MOVEMENT)
                    && cen.getEntity().isImmobile()) {
                    cen.moved = true;
                } else if (null == result) {
                    short_circuit = true;
                } else if (!cen.moved) {
                    if (6 > result.length) {
                        min = 0 < result.length ? result[0] : null;
                        short_circuit = true;
                    }
                    possible.add(result);
                }
            }

            // should ignore mechs that are not engaged
            // and only do the below when there are 2 or mechs left to move
            if (!short_circuit) {
                if ((1 < getEntitiesOwned().size()) && (0 < possible.size())) {
                    final GALance lance = new GALance(this, possible, 50, 80);
                    lance.evolve();
                    min = lance.getResult();
                    old_moves = lance;
                } else if (0 < possible.size() && (null != possible.get(0))
                           && (0 < possible.get(0).length)) {
                    min = possible.get(0)[0];
                }
            }
        }
        if (null == min) {
            min = new MoveOption(game, centities.get(getFirstEntityNum()));
        }
        for (final Object element : enemy_array) {
            final Entity en = (Entity) element;

            // ignore loaded units
            if (null == en.getPosition()) {
                continue;
            }

            final CEntity enemy = centities.get(en);
            final int enemy_hit_arc = CEntity.getThreatHitArc(
                    enemy.current.getFinalCoords(),
                    enemy.current.getFinalFacing(), min.getFinalCoords());
            final MoveOption.DamageInfo di = min.damageInfos.get(enemy);
            if (null != di) {
                enemy.expected_damage[enemy_hit_arc] += di.min_damage;
            }
            if (0 < enemy.expected_damage[enemy_hit_arc]) {
                enemy.hasTakenDamage = true;
            }
        }
        if (min.isPhysical) {
            centities.get(min.getPhysicalTargetId()).isPhysicalTarget = true;
        }
        LOGGER.log(getClass(), methodName, DEBUG, min.toString());
        min.getCEntity().current = min;
        min.getCEntity().last = min;
        min.getCEntity().moved = true;

        final long exit = System.currentTimeMillis();
        LOGGER.log(getClass(), methodName, DEBUG, "move turn took " + (exit - enter) + " ms");

        // If this unit has a jammed RAC, and it has only walked,
        // add an unjam action
        if (null != min.getLastStep()) {
            if (min.getCEntity().entity.canUnjamRAC()) {
                if ((EntityMovementType.MOVE_WALK == min.getLastStep().getMovementType(true))
                    || (EntityMovementType.MOVE_VTOL_WALK == min.getLastStep().getMovementType(true))
                    || (EntityMovementType.MOVE_NONE == min.getLastStep().getMovementType(true))) {
                    // Cycle through all available weapons, only unjam if the
                    // jam(med)
                    // RACs count for a significant portion of possible damage
                    int rac_damage = 0;
                    int other_damage = 0;
                    int clearance_range = 0;
                    for (final Mounted equip : min.getCEntity().entity
                            .getWeaponList()) {
                        final WeaponType test_weapon;

                        test_weapon = (WeaponType) equip.getType();
                        if (((AmmoType.T_AC_ROTARY == test_weapon.getAmmoType())
                             || (game.getBooleanOption(OptionsConstants.ADVCOMBAT_UAC_TWOROLLS)
                                 && ((AmmoType.T_AC_ULTRA == test_weapon.getAmmoType())
                                     || (AmmoType.T_AC_ULTRA_THB == test_weapon.getAmmoType()))))
                            && (equip.isJammed())) {
                            rac_damage = rac_damage + (4 * (test_weapon.getDamage()));
                        } else {
                            if (equip.canFire()) {
                                other_damage += test_weapon.getDamage();
                                if (test_weapon.getMediumRange() > clearance_range) {
                                    clearance_range = test_weapon.getMediumRange();
                                }
                            }
                        }
                    }
                    // Even if the jammed RAC doesn't make up a significant
                    // portion
                    // of the units damage, its still better to have it
                    // functional
                    // If nothing is "close" then unjam anyways
                    int check_range = 100;
                    for (final Entity enemy : game.getEntitiesVector()) {
                        if ((null != min.getCEntity().entity.getPosition())
                            && (null != enemy.getPosition())
                            && (enemy.isEnemyOf(min.getCEntity().entity))) {
                            if (enemy.isVisibleToEnemy()) {
                                if (min.getCEntity().entity.getPosition()
                                                           .distance(enemy.getPosition()) < check_range) {
                                    check_range = min.getCEntity().entity
                                            .getPosition().distance(
                                                    enemy.getPosition());
                                }
                            }
                        }
                    }
                    if ((rac_damage >= other_damage)
                        || (check_range < clearance_range)) {
                        min.addStep(MoveStepType.UNJAM_RAC);
                    }
                }
            }
        }

        return min;
    }

    private MoveOption[] calculateMove(final Entity entity) {
        final List<Entity> enemy_array = myEnemies(entity);
        final ArrayList<Entity> entities = new ArrayList<>(
                game.getEntitiesVector());
        final CEntity self = centities.get(entity);
        MoveOption[] move_array;
        final int friends = entities.size() - enemy_array.size();

        move_array = secondPass(self, friends, enemy_array, entities);
        // top balanced
        filterMoves(move_array, self.pass, new MoveOption.WeightedComparator(1,
                                                                             1), 50);
        // top damage
        filterMoves(move_array, self.pass, new MoveOption.WeightedComparator(
                .5, 1), 50);

        move_array = thirdPass(self, enemy_array);

        // top balanced
        filterMoves(move_array, self.pass, new MoveOption.WeightedComparator(1,
                                                                             1), 30);
        // top damage
        filterMoves(move_array, self.pass, new MoveOption.WeightedComparator(
                .5, 1), 30);

        // reduce self threat, and add bonus for terrain
        for (final MoveOption option : self.pass.values()) {
            option.setState();
            option.self_damage *= .5;
            option.self_threat *= .5;
            // TODO: should scale to the unit bv
            final double terrain = 2 * ((double) Compute.getTargetTerrainModifier(
                    game, option.getEntity()).getValue());
            if (debug) {
                option.tv.add(terrain + " Terrain Adjusment " + "\n");
            }
            option.self_threat -= terrain;
        }

        move_array = fourthPass(self, enemy_array);
        // top balanced
        filterMoves(move_array, self.pass, new MoveOption.WeightedComparator(1,
                                                                             1), 20);
        // top damage
        filterMoves(move_array, self.pass, new MoveOption.WeightedComparator(
                .5, 1), 20);

        // reduce transient damage estimates
        for (final MoveOption option : self.pass.values()) {
            option.self_threat *= .5;
            option.self_damage *= .5;
        }

        move_array = fifthPass(self, enemy_array);

        /*
         * Return top twenty moves to the lance algorithm
         */
        final MoveOption[] result = new MoveOption[Math.min(move_array.length, 20)];
        int offset = 0;
        for (int i = 0; i < Math.min(move_array.length, 20); i++) {
            MoveOption next = move_array[i];
            if (next.isPhysical
                && (5 < self.range_damages[CEntity.RANGE_SHORT])
                && next.doomed) {
                if ((offset + 20) < move_array.length) {
                    next = move_array[offset + 20];
                    offset++;
                }
            }
            result[i] = next;
        }
        return result;
    }

    private List<Entity> myEnemies(final Entity me) {
        final List<Entity> possibles = game.getValidTargets(me);
        final List<Entity> retVal = new ArrayList<>();
        for (final Entity ent : possibles) {
            if (ent.isEnemyOf(me)) {
                retVal.add(ent);
            }
        }
        return retVal;
    }

    /**
     * ************************************************************************
     * first pass, filter moves based upon present case
     * ************************************************************************
     */
    private void firstPass(final CEntity self) {
        final List<Entity> enemies = getEnemyEntities();
        final MoveOption[] move_array;
        if (self.getEntity().isSelectableThisTurn() && !self.moved) {
            move_array = self.getAllMoves(this).values()
                             .toArray(new MoveOption[0]);
        } else {
            move_array = new MoveOption[]{self.current};
        }
        LOGGER.log(getClass(), "firstPass(CEntity)", DEBUG, self.getEntity().getShortName() + " has "
                                                            + move_array.length + " moves");
        for (final MoveOption option : move_array) {
            option.setState();
            final boolean aptPiloting = option.getEntity().getCrew().getOptions()
                                              .booleanOption(OptionsConstants.PILOT_APTITUDE_PILOTING);
            for (int e = 0; e < enemies.size(); e++) { // for each enemy
                final Entity en = enemies.get(e);

                // ignore loaded units
                if (null == en.getPosition()) {
                    continue;
                }

                final CEntity enemy = centities.get(en);
                final int[] modifiers = option.getModifiers(enemy.getEntity());
                if ((TargetRoll.IMPOSSIBLE == modifiers[MoveOption.DEFENCE_MOD])
                    && (TargetRoll.IMPOSSIBLE == modifiers[MoveOption.ATTACK_MOD])) {
                    continue;
                }
                final int enemy_hit_arc = CEntity
                        .getThreatHitArc(enemy.current.getFinalCoords(),
                                         enemy.current.getFinalFacing(),
                                         option.getFinalCoords());
                final int self_hit_arc = CEntity.getThreatHitArc(
                        option.getFinalCoords(), option.getFinalFacing(),
                        enemy.current.getFinalCoords());
                if (!enemy.getEntity().isImmobile()
                    && (TargetRoll.IMPOSSIBLE != modifiers[MoveOption.DEFENCE_MOD])) {
                    self.engaged = true;
                    final int mod = modifiers[MoveOption.DEFENCE_MOD];
                    double max = option.getMaxModifiedDamage(enemy.current,
                                                             mod, modifiers[MoveOption.DEFENCE_PC]);
                    if (en.isSelectableThisTurn()) {
                        enemy.current.addStep(MoveStepType.TURN_RIGHT);
                        max = Math.max(option.getMaxModifiedDamage(
                                enemy.current, mod + 1,
                                modifiers[MoveOption.DEFENCE_PC]), max);
                        enemy.current.removeLastStep();
                        enemy.current.addStep(MoveStepType.TURN_LEFT);
                        max = Math.max(option.getMaxModifiedDamage(
                                enemy.current, mod + 1,
                                modifiers[MoveOption.DEFENCE_PC]), max);
                        // return to original facing
                        enemy.current.removeLastStep();
                    }
                    max = self.getThreatUtility(max, self_hit_arc);
                    if (enemy.getEntity().isProne()) {
                        max *= enemy.base_psr_odds;
                    }
                    final MoveOption.DamageInfo di = option
                            .getDamageInfo(enemy, true);
                    di.threat = max;
                    di.max_threat = max;
                    option.threat += max;
                    if (debug) {
                        option.tv.add(max + " Threat " + e + "\n");
                    }
                }
                /*
                 * As a first approximation, take the maximum to a single target
                 */
                if (!option.isPhysical) {
                    if (TargetRoll.IMPOSSIBLE != modifiers[MoveOption.ATTACK_MOD]) {
                        self.engaged = true;
                        double max = enemy.current.getMaxModifiedDamage(option,
                                                                        modifiers[0], modifiers[MoveOption.ATTACK_PC]);
                        max = enemy.getThreatUtility(max, enemy_hit_arc);
                        final MoveOption.DamageInfo di = option.getDamageInfo(enemy,
                                                                              true);
                        di.damage = max;
                        di.min_damage = max;
                        if (debug) {
                            option.tv.add(max + " Damage " + e + "\n");
                        }
                        option.damage = Math.max(max, option.damage);
                    }
                } else {
                    final CEntity target = centities
                            .get(option.getPhysicalTargetId());
                    try {
                        if (target.getEntity().getId() == enemy.getEntity()
                                                               .getId()) {
                            if (!target.isPhysicalTarget) {
                                final ToHitData toHit;
                                double self_threat = 0;
                                double damage = 0;
                                if (option.isJumping()
                                    && option.getEntity().canDFA()) {
                                    self.current.setState();
                                    toHit = DfaAttackAction.toHit(game, option
                                            .getEntity().getId(), target
                                                                          .getEntity(), option);
                                    damage = 2 * DfaAttackAction
                                            .getDamageFor(
                                                    option.getEntity(),
                                                    (target.getEntity() instanceof Infantry)
                                                    && !(target
                                                            .getEntity() instanceof BattleArmor)
                                                         );
                                    self_threat = (option
                                                           .getCEntity()
                                                           .getThreatUtility(
                                                                   DfaAttackAction.getDamageTakenBy(option
                                                                                                            .getEntity()),
                                                                   ToHitData.SIDE_REAR
                                                                            ) * Compute
                                            .oddsAbove(toHit.getValue(), aptPiloting)) / 100;
                                    self_threat += option.getCEntity()
                                                         .getThreatUtility(
                                                                 .1 * self.getEntity()
                                                                          .getWeight(),
                                                                 ToHitData.SIDE_REAR
                                                                          );
                                    self_threat *= 100 / option.getCEntity()
                                                               .getEntity().getWeight();
                                } else if (option.getEntity().canCharge()) {
                                    self.current.setState();
                                    toHit = new ChargeAttackAction(
                                            option.getEntity(),
                                            target.getEntity()).toHit(game,
                                                                      option);
                                    damage = ChargeAttackAction.getDamageFor(
                                            option.getEntity(),
                                            target.getEntity(), false,
                                            option.getHexesMoved());
                                    self_threat = option
                                                          .getCEntity()
                                                          .getThreatUtility(
                                                                  ChargeAttackAction
                                                                          .getDamageTakenBy(
                                                                                  option.getEntity(),
                                                                                  target.getEntity()),
                                                                  ToHitData.SIDE_FRONT
                                                                           )
                                                  * (Compute.oddsAbove(toHit.getValue(), aptPiloting) / 100);
                                    option.setState();
                                } else {
                                    toHit = new ToHitData(
                                            TargetRoll.IMPOSSIBLE, "");
                                }
                                damage = (target.getThreatUtility(damage,
                                                                  toHit.getSideTable()) * Compute.oddsAbove(toHit.getValue(), aptPiloting)) / 100;
                                // charging is a good tactic against larger
                                // mechs
                                if (!option.isJumping()) {
                                    damage *= Math.sqrt((double) enemy.bv
                                                        / (double) self.bv);
                                }
                                // these are always risky, just don't on 11 or
                                // 12
                                if (10 < toHit.getValue()) {
                                    damage = 0;
                                }
                                // 7 or less is good
                                if (8 > toHit.getValue()) {
                                    damage *= 1.5;
                                }
                                // this is all you are good for
                                if (5 > self.range_damages[CEntity.RANGE_SHORT]) {
                                    damage *= 2;
                                }
                                final MoveOption.DamageInfo di = option
                                        .getDamageInfo(enemy, true);
                                di.damage = damage;
                                di.min_damage = damage;
                                option.damage = damage;
                                option.movement_threat += self_threat;
                            } else {
                                option.threat += Integer.MAX_VALUE;
                            }
                        }
                    } catch (final Exception e1) {
                        e1.printStackTrace();
                        option.threat += Integer.MAX_VALUE;
                    }
                }
            } // -- end while of each enemy
            self.current.setState();
        } // -- end while of first pass
        // top balanced
        filterMoves(move_array, self.pass, new MoveOption.WeightedComparator(1,
                                                                             1), 100);
        // top damage
        filterMoves(move_array, self.pass, new MoveOption.WeightedComparator(
                .5, 1), 100);
    }

    /**
     * ********************************************************************
     * Second pass, combination moves/firing based only on the present case,
     * since only one mech moves at a time
     * ********************************************************************
     */
    private MoveOption[] secondPass(final CEntity self,
                                    final int friends,
                                    final List<Entity> enemy_array,
                                    final ArrayList<Entity> entities) {
        final MoveOption[] move_array = self.pass.values().toArray(new MoveOption[0]);
        self.pass.clear();
        for (int j = 0; (j < move_array.length) && (2 < friends); j++) {
            final MoveOption option = move_array[j];
            for (final Entity en : enemy_array) {
                final CEntity enemy = centities.get(en);
                for (final Entity other : entities) {
                    if (other.isEnemyOf(self.entity)) {
                        continue;
                    }
                    final MoveOption foption = centities.get(other).current;
                    double threat_divisor = 1;
                    final DamageInfo di = option
                            .getDamageInfo(enemy, true);
                    if (null != foption.getDamageInfo(enemy, false)) {
                        option.damage += (enemy.canMove() ? .1 : .2)
                                         * di.damage;
                        threat_divisor += foption.getCEntity().canMove() ? .4
                                                                         : .6;
                    }
                    option.threat -= di.threat;
                    di.threat /= threat_divisor;
                    option.threat += di.threat;
                }
            }
        }
        return move_array;
    }

    /**
     * ********************************************************************
     * third pass, (not so bad) oppurtunistic planner gives preference to good
     * ranges/defensive positions based upon the mech characterization
     * ********************************************************************
     */
    private MoveOption[] thirdPass(final CEntity self,
                                   final List<Entity> enemy_array) {
        final MoveOption[] move_array = self.pass.values().toArray(new MoveOption[0]);
        self.pass.clear();

        for (final MoveOption option : move_array) {
            option.setState();
            double adjustment = 0;
            double temp_adjustment = 0;
            for (final Entity en : enemy_array) { // for each enemy
                final CEntity enemy = centities.get(en);
                final int current_range = self.current.getFinalCoords().distance(
                        enemy.current.getFinalCoords());
                final int range = option.getFinalCoords().distance(
                        enemy.current.getFinalCoords());
                if (range > self.long_range) {
                    temp_adjustment += (!(range < enemy.long_range) ? .5 : 1)
                                       * (1 + self.range_damages[self.range])
                                       * (Math.max(
                            range
                            - self.long_range
                            - (.5 * Math.max(self.jumpMP,
                                             .8 * self.runMP)), 0
                    ));
                }
                if (((CEntity.RANGE_SHORT == self.range) && ((5 < current_range) || (9 < range)))
                    || ((4 > self.range_damages[CEntity.RANGE_SHORT]) && (10 < current_range))) {
                    temp_adjustment += ((CEntity.RANGE_SHORT < enemy.range) ? .5
                                                                            : 1)
                                       * (Math.max(
                            1 + self.range_damages[CEntity.RANGE_SHORT],
                            5))
                                       * Math.max(
                            range
                            - (.5 * Math.max(self.jumpMP,
                                             .8 * self.runMP)), 0
                    );
                } else if (CEntity.RANGE_MEDIUM == self.range) {
                    temp_adjustment += (((6 > current_range) || (12 < current_range)) ? 1
                                                                                      : .25)
                                       * ((CEntity.RANGE_SHORT < enemy.range) ? .5 : 1)
                                       * (1 + self.range_damages[CEntity.RANGE_MEDIUM])
                                       * Math.abs(range
                                                  - (.5 * Math.max(self.jumpMP,
                                                                   .8 * self.runMP)));
                } else if (option.damage < (.25 * self.range_damages[CEntity.RANGE_LONG])) {
                    temp_adjustment += ((10 > range) ? .25 : 1)
                                       * (Math.max(
                            1 + self.range_damages[CEntity.RANGE_LONG],
                            3)) * (1 / (1 + option.threat));
                }
                adjustment += Math.sqrt((temp_adjustment * enemy.bv) / self.bv);
                // I would always like to face the opponent
                if (!(enemy.getEntity().isProne() || enemy.getEntity()
                                                          .isImmobile())
                    && (ToHitData.SIDE_FRONT != CEntity.getThreatHitArc(option.getFinalCoords(),
                                                                        option.getFinalFacing(), enemy.getEntity()
                                                                                                      .getPosition()
                ))) {
                    final int fa = CEntity.getFiringAngle(option.getFinalCoords(),
                                                          option.getFinalFacing(), enemy.getEntity()
                                                                                        .getPosition()
                    );
                    if ((90 < fa) && (270 > fa)) {
                        final int distance = option.getFinalCoords().distance(
                                enemy.current.getFinalCoords());
                        double mod = 1;
                        if ((130 < fa) && (240 > fa)) {
                            mod = 2;
                        }
                        // big formula that says don't do it
                        mod *= (((5 > Math.max(self.jumpMP, .8 * self.runMP)) ? 2
                                                                              : 1)
                                * ((double) self.bv / (double) 50) * Math
                                        .sqrt(((double) self.bv) / enemy.bv))
                               / (((double) distance / 6) + 1);
                        option.self_threat += mod;
                        if (debug) {
                            option.tv.add(mod + " " + fa + " Back to enemy\n");
                        }
                    }
                }
            }
            adjustment *= (self.overall_armor_percent * self.strategy.attack)
                          / enemy_array.size();
            // fix for hiding in level 2 water
            // To a greedy bot, it always seems nice to stay in here...
            final IHex h = game.getBoard().getHex(option.getFinalCoords());
            if (h.containsTerrain(Terrains.WATER)
                && (h.surface() > (self.getEntity().getElevation() + ((option
                    .getFinalProne()) ? 0 : 1)))) {
                final double mod = (7 >= (self.getEntity().heat + option
                        .getMovementheatBuildup())) ? 100 : 30;
                adjustment += self.bv / mod;
            }
            // add them in now, then re-add them later
            if (CEntity.RANGE_SHORT < self.range) {
                final int ele_dif = game.getBoard().getHex(option.getFinalCoords())
                                        .getLevel()
                                    - game.getBoard().getHex(self.current.getFinalCoords())
                                    .getLevel();
                adjustment -= (Math.max(ele_dif, 0) + 1)
                              * ((double) Compute.getTargetTerrainModifier(game,
                                                                           option.getEntity()).getValue() + 1);
            }

            // close the range if nothing else and healthy
            if ((option.damage < (.25 * self.range_damages[self.range]))
                && (adjustment < self.range_damages[self.range])) {
                for (int e = 0; e < enemy_array.size(); e++) {
                    final Entity en = enemy_array.get(e);
                    final CEntity enemy = centities.get(en);
                    final int range = option.getFinalCoords().distance(
                            enemy.current.getFinalCoords());
                    if (5 < range) {
                        adjustment += (Math.pow(self.overall_armor_percent, 2) * Math
                                .sqrt(((double) (range - 4) * enemy.bv)
                                      / self.bv))
                                      / enemy_array.size();
                    }
                }
            }

            if (option.damage < (.25 * (1 + self.range_damages[self.range]))) {
                option.self_threat += 2 * adjustment;
            } else if (option.damage < (.5 * (1 + self.range_damages[self.range]))) {
                option.self_threat += adjustment;
            }
            if (debug) {
                option.tv.add(option.self_threat
                              + " Initial Damage Adjustment " + "\n");
            }
        }

        return move_array;
    }

    // pass should contains 30 ~ 60

    /**
     * ********************************************************************
     * fourth pass, speculation on top moves use averaging to filter
     * ********************************************************************
     */
    private MoveOption[] fourthPass(final CEntity self,
                                    final List<Entity> enemy_array) {
        final MoveOption[] move_array = self.pass.values().toArray(new MoveOption[0]);
        self.pass.clear();
        for (int e = 0; e < enemy_array.size(); e++) { // for each enemy
            final Entity en = enemy_array.get(e);
            final CEntity enemy = centities.get(en);
            // engage in speculation on "best choices" when you loose iniative
            if (enemy.canMove()) {
                final ArrayList<MoveOption> enemy_move_array = enemy.pass.getArray();
                final ArrayList<MoveOption> to_check = new ArrayList<>();
                // check some enemy moves
                for (final MoveOption element : move_array) {
                    final MoveOption option;
                    to_check.clear();
                    option = element;
                    option.setState();
                    // check for damning hexes specifically
                    // could also look at intervening defensive
                    final ArrayList<Coords> coord = new ArrayList<>();
                    final Coords back = option.getFinalCoords().translated(
                            (option.getFinalFacing() + 3) % 6);
                    coord.add(back);
                    coord.add(back.translated((option.getFinalFacing() + 2) % 6));
                    coord.add(back.translated((option.getFinalFacing() + 4) % 6));
                    coord.add(option.getFinalCoords().translated(
                            (option.getFinalFacing())));
                    coord.add(option.getFinalCoords().translated(
                            (option.getFinalFacing() + 1) % 6));
                    coord.add(option.getFinalCoords().translated(
                            (option.getFinalFacing() + 2) % 6));
                    coord.add(option.getFinalCoords().translated(
                            (option.getFinalFacing() + 4) % 6));
                    coord.add(option.getFinalCoords().translated(
                            (option.getFinalFacing() + 5) % 6));
                    for (final Coords test : coord) {
                        final List<MoveOption> c = enemy.findMoves(test, this);
                        if (0 != c.size()) {
                            to_check.addAll(c);
                        }
                    }
                    final int range = option.getFinalCoords().distance(
                            enemy.current.getFinalCoords());
                    int compare = 0;
                    if ((enemy.long_range) > (range - Math.max(enemy.jumpMP,
                                                               enemy.runMP))) {
                        compare = 30;
                    } else if (enemy.long_range > range) {
                        compare = 10;
                    }
                    final double mod = enemies_moved / getEnemyEntities().size();
                    compare *= (1 + mod);
                    for (int k = 0; (k <= compare)
                                    && (k < enemy_move_array.size()); k++) {
                        if (enemy_move_array.size() < compare) {
                            to_check.add(enemy_move_array.get(k));
                        } else {
                            final int value = Compute.randomInt(enemy_move_array
                                                                  .size());
                            if (1 == (value % 2)) {
                                to_check.add(enemy_move_array.get(value));
                            } else {
                                to_check.add(enemy_move_array.get(k));
                            }
                        }
                    }
                    for (final MoveOption enemy_option : to_check) {
                        double max_threat = 0;
                        double max_damage = 0;
                        enemy_option.setState();
                        int enemy_hit_arc = CEntity.getThreatHitArc(
                                enemy_option.getFinalCoords(),
                                enemy_option.getFinalFacing(),
                                option.getFinalCoords());
                        final int self_hit_arc = CEntity.getThreatHitArc(
                                enemy_option.getFinalCoords(),
                                enemy_option.getFinalFacing(),
                                option.getFinalCoords());
                        if (enemy_option.isJumping()) {
                            enemy_hit_arc = Compute.ARC_FORWARD;
                        }
                        final int[] modifiers = option.getModifiers(enemy_option
                                                                            .getEntity());
                        if (TargetRoll.IMPOSSIBLE != modifiers[1]) {
                            self.engaged = true;
                            if (!enemy_option.isJumping()) {
                                max_threat = option.getMaxModifiedDamage(
                                        enemy_option, modifiers[1],
                                        modifiers[MoveOption.DEFENCE_PC]);
                            } else {
                                final boolean enemyAptGunnery = enemy.getEntity().getCrew().getOptions()
                                                                     .booleanOption(OptionsConstants.PILOT_APTITUDE_GUNNERY);
                                max_threat = .8 * enemy
                                        .getModifiedDamage(
                                                (1 == modifiers[MoveOption.DEFENCE_PC]) ? CEntity.TT
                                                                                        : ToHitData.SIDE_FRONT,
                                                enemy_option
                                                        .getFinalCoords()
                                                        .distance(
                                                                option.getFinalCoords()),
                                                modifiers[1], enemyAptGunnery);
                            }
                            max_threat = self.getThreatUtility(max_threat,
                                                               self_hit_arc);
                        }
                        if (TargetRoll.IMPOSSIBLE != modifiers[0]) {
                            self.engaged = true;
                            max_damage = enemy_option.getMaxModifiedDamage(
                                    option, modifiers[0],
                                    modifiers[MoveOption.ATTACK_PC]);
                            max_damage = enemy.getThreatUtility(max_damage,
                                                                enemy_hit_arc);
                            if (option.isPhysical) {
                                if (centities.get(option.getPhysicalTargetId())
                                             .getEntity().getId() == enemy
                                            .getEntity().getId()) {
                                    max_damage = option.getDamage(enemy);
                                } else {
                                    max_damage = 0;
                                }
                            }
                        }
                        final DamageInfo di = option.getDamageInfo(enemy,
                                                                   true);
                        di.max_threat = Math.max(max_threat, di.max_threat);
                        di.min_damage = Math.min(di.min_damage, max_damage);
                        if ((max_threat - max_damage) > (di.threat - di.damage)) {
                            di.threat = max_threat;
                            di.damage = max_damage;
                            if (debug) {
                                option.tv.add(max_threat + " Spec Threat " + e
                                              + "\n");
                                option.tv.add(max_damage + " Spec Damage " + e
                                              + "\n");
                            }
                        }
                    }
                    // update estimates
                    option.damage = 0;
                    option.threat = 0;
                    for (final CEntity cen : option.damageInfos.keySet()) {
                        // rescale
                        final MoveOption.DamageInfo di = option.getDamageInfo(cen,
                                                                              true);
                        di.min_damage /= cen.strategy.target;
                        di.damage /= cen.strategy.target;
                        option.damage += (di.min_damage + di.damage) / 2;

                        // my threat is average of absolute worst, and expected
                        option.threat = Math.max(option.threat, di.max_threat
                                                                + di.threat) / 2;
                        di.threat = (di.max_threat + (2 * di.threat)) / 3;
                    }
                }
                // restore enemy
                enemy.current.setState();
            }
            self.current.setState();
        } // --end move speculation
        return move_array;
    }

    // pass should now be 20 ~ 40

    /**
     * ********************************************************************
     * fifth pass, final damage and threat approximation --prevents moves that
     * from the previous pass would cause the mech to die
     * ********************************************************************
     */
    private MoveOption[] fifthPass(final CEntity self,
                                   final List<Entity> enemy_array) {
        final MoveOption[] move_array = self.pass.values().toArray(new MoveOption[0]);
        self.pass.clear();

        if (self.engaged) {
            for (final MoveOption option : move_array) {
                option.setState();
                final GAAttack temp = this.bestAttack(option);
                if (null != temp) {
                    option.damage = (option.damage + temp
                            .getFittestChromosomesFitness()) / 2;
                } else {
                    option.damage /= 2;
                }
                for (int e = 0; e < enemy_array.size(); e++) { // for each
                    // enemy
                    final Entity en = enemy_array.get(e);
                    final CEntity enemy = centities.get(en);
                    if (!enemy.canMove()) {
                        option.setThreat(
                                enemy,
                                (option.getThreat(enemy) + attackUtility(
                                        enemy.current, self)) / 2
                                        );
                        if (debug) {
                            option.tv.add(option.getThreat(enemy)
                                          + " Revised Threat " + e + " \n");
                        }
                        if (!option.isPhysical) {
                            if (null != temp) {
                                option.setDamage(enemy, (option
                                                                 .getDamage(enemy) + temp
                                                                 .getDamageUtility(enemy)) / 2);
                            } else {
                                // probably zero, but just in case
                                option.setDamage(enemy,
                                                 option.getMinDamage(enemy));
                            }
                            if (debug) {
                                option.tv.add(option.getDamage(enemy)
                                              + " Revised Damage " + e + " \n");
                            }
                            // this needs to be reworked
                            if (1 == option.getFinalCoords().distance(
                                    enemy.current.getFinalCoords())) {
                                PhysicalOption p = PhysicalCalculator
                                        .getBestPhysicalAttack(
                                                option.getEntity(),
                                                enemy.getEntity(), game);
                                if (null != p) {
                                    option.setDamage(enemy,
                                                     option.getDamage(enemy)
                                                     + p.expectedDmg
                                                    );
                                    if (debug) {
                                        option.tv.add(p.expectedDmg
                                                      + " Physical Damage " + e
                                                      + " \n");
                                    }
                                }
                                p = PhysicalCalculator.getBestPhysicalAttack(
                                        enemy.getEntity(), option.getEntity(),
                                        game);
                                if (null != p) {
                                    option.setThreat(enemy,
                                                     option.getThreat(enemy)
                                                     + (.5 * p.expectedDmg)
                                                    );
                                    if (debug) {
                                        option.tv.add((.5 * p.expectedDmg)
                                                      + " Physical Threat " + e
                                                      + " \n");
                                    }
                                }
                            }
                        }
                    } else if (!option.isPhysical) { // enemy can move (not
                        if (null != temp) {
                            option.setDamage(enemy, ((2 * option
                                    .getDamage(enemy)) + temp
                                                             .getDamageUtility(enemy)) / 3);
                        } else {
                            option.setDamage(enemy, option.getMinDamage(enemy));
                        }
                    } else {
                        // get a more accurate estimate
                        option.setDamage(
                                enemy,
                                option.getDamage(enemy)
                                / Math.sqrt((double) enemy.bv
                                            / (double) self.bv)
                                        );
                        option.damage = option.getDamage(enemy);
                    }
                }
                option.threat = 0;
                for (final DamageInfo damageInfo : option.damageInfos.values()) {
                    option.threat += damageInfo.threat;
                }
                if (debug) {
                    option.tv.add(option.threat + " Revised Threat Utility\n");
                    option.tv.add(option.damage + " Revised Damage Utility\n");
                }
            }
        }
        Arrays.sort(move_array, new MoveOption.WeightedComparator(
                1, 1));
        self.current.setState();

        return move_array;
    }

    private void filterMoves(final MoveOption[] move_array,
                             final MoveOption.Table pass,
                             final MoveOption.WeightedComparator comp,
                             final int filter) {
        Arrays.sort(move_array, comp);

        // top 100 utility, mostly conservative
        for (int i = 0; (i < filter) && (i < move_array.length); i++) {
            pass.put(move_array[i]);
        }
    }

    @Override
    protected void initFiring() {
        final ArrayList<Entity> entities = new ArrayList<>(
                game.getEntitiesVector());
        for (int i = 0; i < entities.size(); i++) {
            final Entity entity = entities.get(i);
            final CEntity centity = centities.get(entity);
            centity.reset();
            centity.enemy_num = i;
        }
        for (final Entity entity : getEnemyEntities()) {
            final CEntity centity = centities.get(entity);
            if (entity.isMakingDfa() || entity.isCharging()) {
                // try to prevent a physical attack from happening
                // but should take into account the toHit of the attack
                centity.strategy.target = 2.5;
            }
        }
    }

    private ArrayList<AttackOption> calculateWeaponAttacks(final Entity en,
                                                           final Mounted mw,
                                                           @SuppressWarnings("SameParameterValue") final boolean best_only) {
        final int from = en.getId();
        final int weaponID = en.getEquipmentNum(mw);
        int spin_mode;
        int starg_mod;
        final ArrayList<AttackOption> result = new ArrayList<>();
        final List<Entity> ents = myEnemies(en);
        WeaponAttackAction wep_test;
        WeaponType spinner;
        AttackOption a;
        AttackOption max = new AttackOption(null, null, 0, null, 1, en.getCrew().getOptions()
                                                                      .booleanOption(
                                                                              OptionsConstants.PILOT_APTITUDE_GUNNERY));
        for (final Entity e : ents) {
            final CEntity enemy = centities.get(e);
            // long entry = System.currentTimeMillis();
            final ToHitData th = WeaponAttackAction.toHit(game, from, e, weaponID, false);
            // long exit = System.currentTimeMillis();
            // if (exit != entry)
            // System.out.println("Weapon attack toHit took "+(exit-entry));
            if ((TargetRoll.IMPOSSIBLE != th.getValue())
                && !(13 <= th.getValue())) {
                final double expectedDmg;

                wep_test = new WeaponAttackAction(from, e.getId(), weaponID);

                // If this is an Ultra or Rotary cannon, check for spin up
                spinner = (WeaponType) mw.getType();
                if ((AmmoType.T_AC_ULTRA == spinner.getAmmoType())
                    || (AmmoType.T_AC_ULTRA_THB == spinner.getAmmoType())
                    || (AmmoType.T_AC_ROTARY == spinner.getAmmoType())) {
                    spin_mode = Compute.spinUpCannon(game, wep_test);
                    super.sendModeChange(from, weaponID, spin_mode);
                }

                // Ammo cycler runs each valid ammo type through the weapon
                // while calling for expected damage on each type; best type
                // by damage is loaded

                expectedDmg = Compute.getAmmoAdjDamage(game, wep_test);

                // Get the secondary target modifier for this weapon/target
                // combo

                starg_mod = 1;

                if (-1 != en.getFacing()) {
                    if (en.canChangeSecondaryFacing()) {

                        if (!Compute.isInArc(en.getPosition(),
                                             en.getSecondaryFacing(), e, en.getForwardArc())) {
                            starg_mod = 2;
                        }
                    } else {
                        if (!Compute.isInArc(en.getPosition(), en.getFacing(),
                                             e, en.getForwardArc())) {
                            starg_mod = 2;
                        }

                    }
                }

                // For good measure, infantry cannot attack multiple targets
                if ((en instanceof Infantry) && !(en instanceof BattleArmor)) {
                    starg_mod = 13;
                }

                a = new AttackOption(enemy, mw, expectedDmg, th, starg_mod,
                                     en.getCrew().getOptions().booleanOption(OptionsConstants.PILOT_APTITUDE_GUNNERY));
                if (a.value > max.value) {
                    if (best_only) {
                        max = a;
                    } else {
                        result.add(0, a);
                    }
                } else {
                    result.add(a);
                }
            }
        }
        if (best_only && (null != max.target)) {
            result.add(max);
        }
        if (0 < result.size()) {
            result.add(new AttackOption(null, mw, 0, null, 1, en.getCrew().getOptions()
                                                                .booleanOption(OptionsConstants
                                                                                       .PILOT_APTITUDE_GUNNERY)));
        }
        return result;
    }

    private GAAttack bestAttack(final MoveOption es) {
        return bestAttack(es, null, 2);
    }

    private GAAttack bestAttack(final MoveOption es,
                                final CEntity target,
                                final int search_level) {
        final Entity en = es.getEntity();
        final int[] attacks = new int[4];
        ArrayList<AttackOption> c;
        final ArrayList<ArrayList<AttackOption>> front = new ArrayList<>();
        final ArrayList<ArrayList<AttackOption>> left = new ArrayList<>();
        final ArrayList<ArrayList<AttackOption>> right = new ArrayList<>();
        final ArrayList<ArrayList<AttackOption>> rear = new ArrayList<>();
        GAAttack result = null;
        final int o_facing = en.getFacing();
        double front_la_dmg = 0;
        double front_ra_dmg = 0;
        double left_la_dmg = 0;
        double left_ra_dmg = 0;
        double right_la_dmg = 0;
        double right_ra_dmg = 0;
        PhysicalOption best_front_po = new PhysicalOption(en);
        PhysicalOption best_left_po = new PhysicalOption(en);
        PhysicalOption best_right_po = new PhysicalOption(en);

        // Get best physical attack
        for (final Mounted mw : en.getWeaponList()) {

            // If this weapon is in the same arm as a
            // brush off attack skip to next weapon.
            c = calculateWeaponAttacks(en, mw, true);

            // Get best physical attack
            best_front_po = PhysicalCalculator.getBestPhysical(en, game);

            if ((null != best_front_po) && (en instanceof Mech)) {

                // If this weapon is in the same arm as a brush off attack
                // skip to next weapon

                if (((PhysicalOption.BRUSH_LEFT == best_front_po.type) || (PhysicalOption
                                                                                   .BRUSH_BOTH == best_front_po.type))
                    && (Mech.LOC_LARM == mw.getLocation())) {
                    continue;
                }
                if (((PhysicalOption.BRUSH_RIGHT == best_front_po.type) || (PhysicalOption
                                                                                    .BRUSH_BOTH == best_front_po.type))
                    && (Mech.LOC_RARM == mw.getLocation())) {
                    continue;
                }

                // Total the damage of all weapons fired from each arm
                if (((PhysicalOption.PUNCH_LEFT == best_front_po.type) || (PhysicalOption
                                                                                   .PUNCH_BOTH == best_front_po.type))
                    && (Mech.LOC_LARM == mw.getLocation())) {
                    if (0 < c.size()) {
                        front_la_dmg += c.get(c.size() - 2).value;
                    }
                }
                if (((PhysicalOption.PUNCH_RIGHT == best_front_po.type) || (PhysicalOption
                                                                                    .PUNCH_BOTH == best_front_po.type))
                    && (Mech.LOC_RARM == mw.getLocation())) {
                    if (0 < c.size()) {
                        front_ra_dmg += c.get(c.size() - 2).value;
                    }
                }
                // If this weapon is a push attack and an arm mounted
                // weapon skip to next weapon

                if ((PhysicalOption.PUSH_ATTACK == best_front_po.type)
                    && ((Mech.LOC_LARM == mw.getLocation()) || (Mech.LOC_RARM == mw
                        .getLocation()))) {
                    continue;
                }
            }

            // If this weapon is in the same arm as a punch
            // attack, add the damage to the running total.
            if (0 < c.size()) {
                front.add(c);
                attacks[0] = Math.max(attacks[0], c.size());
            }
            if (!es.getFinalProne() && en.canChangeSecondaryFacing()) {
                en.setSecondaryFacing((o_facing + 5) % 6);
                c = calculateWeaponAttacks(en, mw, true);
                if (0 < c.size()) {
                    // Get best physical attack
                    best_left_po = PhysicalCalculator.getBestPhysical(en, game);
                    if ((null != best_left_po) && (en instanceof Mech)) {
                        if (((PhysicalOption.PUNCH_LEFT == best_left_po.type) || (PhysicalOption
                                                                                          .PUNCH_BOTH == best_left_po.type))
                            && (Mech.LOC_LARM == mw.getLocation())) {
                            left_la_dmg += c.get(c.size() - 2).value;
                        }
                        if (((PhysicalOption.PUNCH_RIGHT == best_left_po.type) || (PhysicalOption.PUNCH_BOTH == best_left_po.type))
                            && (Mech.LOC_RARM == mw.getLocation())) {
                            left_ra_dmg += c.get(c.size() - 2).value;
                        }
                    }
                    left.add(c);
                    attacks[1] = Math.max(attacks[1], c.size());
                }
                en.setSecondaryFacing((o_facing + 1) % 6);
                c = calculateWeaponAttacks(en, mw, true);
                if (0 < c.size()) {
                    // Get best physical attack
                    best_right_po = PhysicalCalculator
                            .getBestPhysical(en, game);
                    if ((null != best_right_po) && (en instanceof Mech)) {
                        if (((PhysicalOption.PUNCH_LEFT == best_right_po.type) || (PhysicalOption.PUNCH_BOTH == best_right_po.type))
                            && (Mech.LOC_LARM == mw.getLocation())) {
                            right_la_dmg += c.get(c.size() - 2).value;
                        }
                        if (((PhysicalOption.PUNCH_RIGHT == best_right_po.type) || (PhysicalOption.PUNCH_BOTH == best_right_po.type))
                            && (Mech.LOC_RARM == mw.getLocation())) {
                            right_ra_dmg += c.get(c.size() - 2).value;
                        }
                    }
                    right.add(c);
                    attacks[2] = Math.max(attacks[2], c.size());
                }
                en.setSecondaryFacing((o_facing + 3) % 6);
                c = calculateWeaponAttacks(en, mw, true);
                if (0 < c.size()) {
                    rear.add(c);
                    attacks[3] = Math.max(attacks[3], c.size());
                }
            } else {
                attacks[1] = 0;
                attacks[2] = 0;
            }
            en.setSecondaryFacing(o_facing);
        }

        fireOrPhysicalCheck(best_front_po, en, front, front_la_dmg,
                            front_ra_dmg);

        final ArrayList<ArrayList<ArrayList<AttackOption>>> arcs = new ArrayList<>();
        arcs.add(front);
        if (!es.getFinalProne() && en.canChangeSecondaryFacing()) {
            fireOrPhysicalCheck(best_left_po, en, left, left_la_dmg,
                                left_ra_dmg);
            arcs.add(left);
            fireOrPhysicalCheck(best_right_po, en, right, right_la_dmg,
                                right_ra_dmg);
            arcs.add(right);
            // Meks and protos can't twist all the way around.
            if (!(en instanceof Mech) && !(en instanceof Protomech)) {
                arcs.add(rear);
            }
        }
        for (int i = 0; i < arcs.size(); i++) {
            final ArrayList<ArrayList<AttackOption>> v = arcs.get(i);
            if (0 < v.size()) {
                final GAAttack test = new GAAttack(this, centities.get(en), v,
                                                   Math.max((v.size() + attacks[i]) * search_level,
                                                      20 * search_level), 30 * search_level,
                                                   en.isEnemyOf(getEntitiesOwned().get(0))
                );
                test.setFiringArc(i);
                test.evolve();
                if (null != target) {
                    if ((null == result)
                        || (test.getDamageUtility(target) > result
                            .getDamageUtility(target))) {
                        result = test;
                    }
                } else if ((null == result)
                           || (test.getFittestChromosomesFitness() > result
                        .getFittestChromosomesFitness())) {
                    result = test;
                }
            }
        }
        return result;
    }

    /**
     * If the best attack is a punch, then check each punch damage against the
     * weapons damage from the appropriate arm; if the punch does more damage,
     * drop the weapons in that arm to 0 expected damage Repeat this for left
     * and right twists
     *
     * @param best_po
     * @param entity
     * @param attackOptions
     * @param la_dmg
     * @param ra_dmg
     */
    private void fireOrPhysicalCheck(final PhysicalOption best_po,
                                     final Entity entity,
                                     final ArrayList<ArrayList<AttackOption>> attackOptions,
                                     final double la_dmg,
                                     final double ra_dmg) {
        ArrayList<AttackOption> c;
        if ((null != best_po) && (entity instanceof Mech)) {
            if (PhysicalOption.PUNCH_LEFT == best_po.type) {
                if ((la_dmg < best_po.expectedDmg)
                    && (0 < attackOptions.size())) {
                    for (final ArrayList<AttackOption> attackOption : attackOptions) {
                        c = attackOption;
                        for (final AttackOption aC : c) {
                            if (Mech.LOC_LARM == aC.weapon.getLocation()) {
                                aC.expected = 0;
                                aC.primary_expected = 0;
                            }
                        }
                    }
                }
            }
            if (PhysicalOption.PUNCH_RIGHT == best_po.type) {
                if ((ra_dmg < best_po.expectedDmg)
                    && (0 < attackOptions.size())) {
                    for (final ArrayList<AttackOption> attackOption : attackOptions) {
                        c = attackOption;
                        for (final AttackOption aC : c) {
                            if (Mech.LOC_RARM == aC.weapon.getLocation()) {
                                aC.expected = 0;
                                aC.primary_expected = 0;
                            }
                        }
                    }
                }
            }
            if (PhysicalOption.PUNCH_BOTH == best_po.type) {
                if (((la_dmg + ra_dmg) < best_po.expectedDmg)
                    && (0 < attackOptions.size())) {
                    for (final ArrayList<AttackOption> attackOption : attackOptions) {
                        c = attackOption;
                        for (final AttackOption aC : c) {
                            if (Mech.LOC_LARM == aC.weapon.getLocation()) {
                                aC.expected = 0;
                                aC.primary_expected = 0;
                            }
                            if (Mech.LOC_RARM == aC.weapon.getLocation()) {
                                aC.expected = 0;
                                aC.primary_expected = 0;
                            }
                        }
                    }
                }
            }
        }
    }

    /* could use best of best strategy instead of expensive ga */
    private double attackUtility(final MoveOption es,
                                 final CEntity target) {
        final GAAttack result = bestAttack(es, target, 1);
        if (null == result) {
            return 0;
        }
        return result.getFittestChromosomesFitness();
    }

    @Override
    public void calculateFiringTurn() {
        final int first_entity = game.getFirstEntityNum(getMyTurn());
        int entity_num = first_entity;
        int best_entity = first_entity;
        int spin_mode;
        double max = java.lang.Double.NEGATIVE_INFINITY;
        int[] results = null;
        ArrayList<ArrayList<AttackOption>> winner = null;
        int arc = 0;
        WeaponType spinner;

        if (-1 == entity_num) {
            return;
        }

        do {
            final Entity en = game.getEntity(entity_num);
            final CEntity cen = centities.get(en);

            final GAAttack test = bestAttack(cen.current, null, 3);

            if ((null != test) && (test.getFittestChromosomesFitness() > max)) {
                max = test.getFittestChromosomesFitness();
                results = test.getResultChromosome();
                arc = test.getFiringArc();
                best_entity = entity_num;
                winner = test.getAttack();
            }
            entity_num = game.getNextEntityNum(getMyTurn(), entity_num);
        } while ((entity_num != first_entity) && (-1 != entity_num));

        final Vector<EntityAction> av = new Vector<>();
        // maximum already selected (or default)
        final Entity en = game.getEntity(best_entity);
        if (null != results) {
            final Entity primary_target = game.getEntitiesVector().get(
                    results[results.length - 1]);
            final TreeSet<AttackOption> tm = new TreeSet<>(
                    new AttackOption.Sorter(centities.get(primary_target)));
            for (int i = 0; i < (results.length - 1); i++) {
                if (null != winner) {
                    final AttackOption a = winner.get(i).get(results[i]);
                    if (null != a.target) {
                        a.target.expected_damage[a.toHit.getSideTable()] += a.value;
                        a.target.hasTakenDamage = true;
                        tm.add(a);
                    }
                }
            }
            for (final AttackOption a : tm) {
                final WeaponAttackAction new_attack = new WeaponAttackAction(
                        en.getId(), a.target.getEntity().getId(),
                        en.getEquipmentNum(a.weapon));

                if (null != en.getEquipment(new_attack.getWeaponId()).getLinked()) {
                    spinner = (WeaponType) a.weapon.getType();

                    // If this is an ultra-cannon or rotary cannon, try to spin
                    // it up

                    if ((AmmoType.T_AC_ULTRA == spinner.getAmmoType())
                        || (AmmoType.T_AC_ULTRA_THB == spinner.getAmmoType())
                        || (AmmoType.T_AC_ROTARY == spinner.getAmmoType())) {
                        spin_mode = Compute.spinUpCannon(game, new_attack);
                        super.sendModeChange(en.getId(),
                                             en.getEquipmentNum(a.weapon), spin_mode);
                    }
                    final Mounted cur_ammo = en
                            .getEquipment(new_attack.getWeaponId()).getLinked();
                    new_attack.setAmmoId(en.getEquipmentNum(cur_ammo));
                    Compute.getAmmoAdjDamage(game, new_attack);

                }
                av.add(new_attack);

            }

            // Use the attack options and weapon attack actions to determine the
            // best aiming point

            if (0 < av.size()) {
                getAimPoint(tm, av);
            }

        }
        switch (arc) {
            case 1:
                av.add(0, new TorsoTwistAction(en.getId(),
                                               (en.getFacing() + 5) % 6));
                break;
            case 2:
                av.add(0, new TorsoTwistAction(en.getId(),
                                               (en.getFacing() + 1) % 6));
                break;
            case 3:
                av.add(0, new TorsoTwistAction(en.getId(),
                                               (en.getFacing() + 3) % 6));
                break;
        }
        sendAttackData(best_entity, av);
    }

    /**
     * consider how to put more pre-turn logic here
     */
    @Override
    protected void initMovement() {
        final String methodName = "initMovement()";
        
        old_moves = null;
        enemies_moved = 0;
        final double max_modifier = 1.4;
        final ArrayList<Entity> entities = new ArrayList<>(
                game.getEntitiesVector());
        final double num_entities = Math.sqrt(entities.size()) / 100;
        final ArrayList<CEntity> friends = new ArrayList<>();
        final ArrayList<CEntity> foes = new ArrayList<>();
        double friend_sum = 0;
        double foe_sum = 0;
        double max_foe_bv = 0;
        CEntity max_foe = null;
        for (int i = 0; i < entities.size(); i++) {
            final Entity entity = entities.get(i);
            final CEntity centity = centities.get(entity);
            centity.enemy_num = i;
            final double old_value = centity.bv * (centity.overall_armor_percent + 1);
            centity.reset(); // should get fresh values
            double new_value = centity.bv * (centity.overall_armor_percent + 1);
            final double percent = 1 + ((new_value - old_value) / old_value);
            if (entity.getOwner().equals(getLocalPlayer())) {
                friends.add(centity);
                friend_sum += new_value;
                if (.85 > percent) {
                    // small retreat
                    centity.strategy.attack = .85;
                } else if (.95 > percent) {
                    centity.strategy.attack = 1;
                } else if ((1 >= percent)
                           && (max_modifier > centity.strategy.attack)) {
                    if (1 == percent) {
                        if (1 > centity.strategy.attack) {
                            centity.strategy.attack = Math.min(
                                    1.4 * centity.strategy.attack, 1);
                        } else {
                            centity.strategy.attack *= (1.0 + num_entities);
                        }
                    } else {
                        centity.strategy.attack *= (1.0 + (2 * num_entities));
                    }
                }
            } else if (!entity.getOwner().isEnemyOf(getLocalPlayer())) {
                friend_sum += new_value;
            } else {
                foes.add(centity);
                foe_sum += new_value;
                if (entity.isCommander()) {
                    new_value *= 3; // make bots like to attack commanders
                }
                if ((new_value > max_foe_bv) || (null == max_foe)) {
                    max_foe_bv = new_value;
                    max_foe = centity;
                }
                if (2 < getEntitiesOwned().size()) {
                    if (2 < centity.strategy.target) {
                        centity.strategy.target = 1 + (.5 * (centity.strategy.target - 2));
                    }
                    if ((.85 > percent)
                        && (max_modifier > centity.strategy.target)) {
                        centity.strategy.target *= (1.0 + (6 * num_entities));
                    } else if ((.95 > percent)
                               && (max_modifier > centity.strategy.target)) {
                        centity.strategy.target *= (1.0 + (4 * num_entities));
                    } else if (1 >= percent) {
                        if (1 == percent) {
                            centity.strategy.target /= (1.0 + (2 * num_entities));
                        } else {
                            centity.strategy.target /= (1.0 + num_entities);
                        }
                    }
                    // don't go below one
                    if (1 > centity.strategy.target) {
                        centity.strategy.target = 1;
                    }
                }
            }
        }
        LOGGER.log(getClass(), methodName, DEBUG, "Us " + friend_sum + " Them " + foe_sum);
        // do some more reasoning...
        final double unit_values = friend_sum;
        final double enemy_values = foe_sum;
        Iterator<CEntity> i = foes.iterator();

        if (1 < friends.size()) {
            if ((null == Strategy.MainTarget)
                || (null == game.getEntity(Strategy.MainTarget.getEntity()
                                                              .getId()))) {
                Strategy.MainTarget = max_foe;
            }
            // TODO : Handle this better.
            if (null == Strategy.MainTarget) {
                LOGGER.log(getClass(),
                           methodName,
                           new RuntimeException("TestBot#initMovement() - no main target for bot"));
            } else if (null == Strategy.MainTarget.strategy) {
                LOGGER.log(getClass(),
                           methodName,
                           new RuntimeException("TestBot#initMovement() - no strategy for main target"));
            } else {
                Strategy.MainTarget.strategy.target += .2;
                while (i.hasNext()) {
                    final CEntity centity = i.next();
                    // good turn, keep up the work, but randomize to reduce
                    // predictability
                    if ((friend_sum - foe_sum) >= ((.9 * unit_values) - enemy_values)) {
                        if (1 == Compute.randomInt(2)) {
                            centity.strategy.target += .3;
                        }
                        // lost that turn, but still in the fight, just get a
                        // little more aggressive
                    } else if (friend_sum > (.9 * foe_sum)) {
                        centity.strategy.target += .15;
                        // lost that turn and loosing
                    } else if (2 > centity.strategy.target) { // go for the
                        // gusto
                        centity.strategy.target += .3;
                    }
                    LOGGER.log(getClass(),
                               methodName,
                               DEBUG,
                               centity.getEntity().getShortName() + " " + centity.strategy.target);
                }
            }
        }

        final double ratio = friend_sum / foe_sum;
        double mod = 1;
        if (.9 > ratio) {
            mod = .95;
        } else //noinspection StatementWithEmptyBody
            if (1 > ratio) {
            // no change
        } else { // attack
            mod = (1.0 + num_entities);
        }
        i = friends.iterator();
        while (i.hasNext()) {
            final CEntity centity = i.next();
            if (!((1 > mod) && (.6 > centity.strategy.attack))
                && !((1 < mod) && (max_modifier <= centity.strategy.attack))) {
                centity.strategy.attack *= mod;
            }
        }
        System.gc(); // just to make sure
    }

    @Override
    protected void processChat(final GamePlayerChatEvent ge) {
        chatp.processChat(ge, this);
    }

    // Where do I put my units? This prioritizes hexes and facings
    @Override
    protected void calculateDeployment() {

        int weapon_count;
        int hex_count;
        int x_ave;
        int y_ave;
        final int nDir;
        double av_range;

        final Coords pointing_to;

        final int entNum = game.getFirstDeployableEntityNum(game.getTurnForPlayer(localPlayerNumber));
        assert (Entity.NONE != entNum) : "The bot is trying to deploy without units being left.";

        final List<Coords> cStart = getStartingCoordsArray(game.getEntity(entNum));
        final Coords cDeploy = getFirstValidCoords(getEntity(entNum), cStart);

        if (null == cDeploy) {
            // bad event handeling, this unit is not deployable, remove it
            // instead.
            // This should not happen but does (eg ships on a deployment zone
            // without water.
            LOGGER.log(getClass(), "calculateDeployment()", WARNING,
                       "The bot does not know how or is unable to deploy " + getEntity(entNum) +
                       ". Removing it instead.");
            sendChat("Oh dear I don't know how to deploy this "
                     + getEntity(entNum) + ". Skipping to the next one.");
            sendDeleteEntity(entNum);
            return;
        }

        // Now that we have a location to deploy to, get a direction
        // Using average long range of deploying unit, point towards the largest
        // cluster of enemies in range

        av_range = 0.0;
        weapon_count = 0;
        for (final Mounted mounted : getEntity(entNum).getWeaponList()) {
            final WeaponType wtype = (WeaponType) mounted.getType();
            if ((!Objects.equals("ATM 3", wtype.getName())) && (!Objects.equals("ATM 6", wtype.getName()))
                && (!Objects.equals("ATM 9", wtype.getName()))
                && (!Objects.equals("ATM 12", wtype.getName()))) {
                if (null != getEntity(entNum).getC3Master()) {
                    av_range += ((wtype.getLongRange()) * 1.25);
                } else {
                    av_range += wtype.getLongRange();
                }
                weapon_count++;
            }
        }
        for (final Mounted mounted : getEntity(entNum).getAmmo()) {
            final AmmoType atype = (AmmoType) mounted.getType();
            if (AmmoType.T_ATM == atype.getAmmoType()) {
                weapon_count++;
                av_range += 15.0;
                if (AmmoType.M_HIGH_EXPLOSIVE == atype.getMunitionType()) {
                    av_range -= 6;
                }
                if (AmmoType.M_EXTENDED_RANGE == atype.getMunitionType()) {
                    av_range += 12.0;
                }
            }
            if (AmmoType.T_MML == atype.getAmmoType()) {
                weapon_count++;
                if (atype.hasFlag(AmmoType.F_MML_LRM)) {
                    av_range = 9;
                } else {
                    av_range = 21.0;
                }
            }
        }

        av_range = av_range / weapon_count;

        hex_count = 0;
        x_ave = 0;
        y_ave = 0;
        for (final Entity test_ent : game.getEntitiesVector()) {
            if (test_ent.isDeployed()) {
                if (test_ent.isVisibleToEnemy()) {
                    if (cDeploy.distance(test_ent.getPosition()) <= (int) av_range) {
                        hex_count++;
                        x_ave += test_ent.getPosition().getX();
                        y_ave += test_ent.getPosition().getY();
                    }
                }
            }
        }
        if (0 != hex_count) {
            pointing_to = new Coords((x_ave / hex_count), (y_ave / hex_count));
        } else {
            pointing_to = new Coords(game.getBoard().getWidth() / 2, game
                                                                             .getBoard().getHeight() / 2);
        }
        nDir = cDeploy.direction(pointing_to);

        // If unit has stealth armor, turn it on
        if ((getEntity(entNum) instanceof Mech)
            && (EquipmentType.T_ARMOR_STEALTH == getEntity(entNum).getArmorType(0))
            && !getEntity(entNum).hasPatchworkArmor()) {
            for (final Mounted test_equip : getEntity(entNum).getMisc()) {
                final MiscType test_type = (MiscType) test_equip.getType();
                if (test_type.hasFlag(MiscType.F_STEALTH)) {
                    if (!test_equip.curMode().getName().equals("On")) {
                        test_equip.setMode("On");
                        super.sendModeChange(entNum, getEntity(entNum)
                                .getEquipmentNum(test_equip), 1);
                    }
                }
            }
        }

        final Entity ce = game.getEntity(entNum);
        assert (!ce.isLocationProhibited(cDeploy)) : "Bot tried to deploy to an invalid hex";
        deploy(entNum, cDeploy, nDir, 0);
    }

    @Override
    protected MovePath continueMovementFor(final Entity entity) {
        final String methodName = "continueMovementFor(Entity)";

        if (null == entity) {
            throw new NullPointerException("Entity is null.");
        }

        LOGGER.log(getClass(), methodName, DEBUG,
                   "Contemplating movement of " + entity.getShortName() + " " + entity.getId());
        final CEntity cen = centities.get(entity);
        cen.refresh();
        firstPass(cen);

        final Object[] enemy_array = getEnemyEntities().toArray();
        final MoveOption[] result = calculateMove(entity);
        MoveOption min = null;
        final ArrayList<MoveOption[]> possible = new ArrayList<>();
        boolean short_circuit = false;

        if (6 > result.length) {
            min = 0 < result.length ? result[0] : null;
            short_circuit = true;
        }
        possible.add(result);

        // should ignore mechs that are not engaged
        // and only do the below when there are 2 or mechs left to move
        if (!short_circuit) {
            if ((1 < getEntitiesOwned().size()) && (0 < possible.size())) {
                final GALance lance = new GALance(this, possible, 50, 80);
                lance.evolve();
                min = lance.getResult();
                old_moves = lance;
            } else if ((null != possible.get(0))
                       && (0 < possible.get(0).length)) {
                min = possible.get(0)[0];
            }
        }
        if (null == min) {
            min = new MoveOption(game, centities.get(getFirstEntityNum()));
        }

        for (final Object element : enemy_array) {
            final Entity en = (Entity) element;

            // ignore loaded units
            if (null == en.getPosition()) {
                continue;
            }

            final CEntity enemy = centities.get(en);
            final int enemy_hit_arc = CEntity.getThreatHitArc(
                    enemy.current.getFinalCoords(),
                    enemy.current.getFinalFacing(), min.getFinalCoords());
            final MoveOption.DamageInfo di = min.damageInfos.get(enemy);
            if (null != di) {
                enemy.expected_damage[enemy_hit_arc] += di.min_damage;
            }
            if (0 < enemy.expected_damage[enemy_hit_arc]) {
                enemy.hasTakenDamage = true;
            }
        }
        if (min.isPhysical) {
            centities.get(min.getPhysicalTargetId()).isPhysicalTarget = true;
        }
        LOGGER.log(getClass(), methodName, DEBUG, min.toString());
        min.getCEntity().current = min;
        min.getCEntity().last = min;
        min.getCEntity().moved = true;

        // If this unit has a jammed RAC, and it has only walked,
        // add an unjam action
        if (null != min.getLastStep()) {
            if (min.getCEntity().entity.canUnjamRAC()) {
                if ((EntityMovementType.MOVE_WALK == min.getLastStep().getMovementType(true))
                    || (EntityMovementType.MOVE_VTOL_WALK == min.getLastStep().getMovementType(true))
                    || (EntityMovementType.MOVE_NONE == min.getLastStep().getMovementType(true))) {
                    // Cycle through all available weapons, only unjam if the
                    // jam(med)
                    // RACs count for a significant portion of possible damage
                    int rac_damage = 0;
                    int other_damage = 0;
                    int clearance_range = 0;
                    for (final Mounted equip : min.getCEntity().entity
                            .getWeaponList()) {
                        final WeaponType test_weapon;

                        test_weapon = (WeaponType) equip.getType();
                        if ((AmmoType.T_AC_ROTARY == test_weapon.getAmmoType())
                            && (equip.isJammed())) {
                            rac_damage = rac_damage
                                         + (4 * (test_weapon.getDamage()));
                        } else {
                            if (equip.canFire()) {
                                other_damage += test_weapon.getDamage();
                                if (test_weapon.getMediumRange() > clearance_range) {
                                    clearance_range = test_weapon
                                            .getMediumRange();
                                }
                            }
                        }
                    }
                    // Even if the jammed RAC doesn't make up a significant
                    // portion
                    // of the units damage, its still better to have it
                    // functional
                    // If nothing is "close" then unjam anyways
                    int check_range = 100;
                    for (final Entity enemy : game.getEntitiesVector()) {
                        if ((null != min.getCEntity().entity.getPosition())
                            && (null != enemy.getPosition())
                            && (enemy.isEnemyOf(min.getCEntity().entity))) {
                            if (enemy.isVisibleToEnemy()) {
                                if (min.getCEntity().entity.getPosition()
                                                           .distance(enemy.getPosition()) < check_range) {
                                    check_range = min.getCEntity().entity
                                            .getPosition().distance(
                                                    enemy.getPosition());
                                }
                            }
                        }
                    }
                    if ((rac_damage >= other_damage)
                        || (check_range < clearance_range)) {
                        min.addStep(MoveStepType.UNJAM_RAC);
                    }
                }
            }
        }

        return min;
    }

    @Override
    protected Vector<Minefield> calculateMinefieldDeployment() {
        final Vector<Minefield> deployedMinefields = new Vector<>();

        deployMinefields(deployedMinefields, getLocalPlayer()
                .getNbrMFConventional(), 0);
        deployMinefields(deployedMinefields,
                         getLocalPlayer().getNbrMFCommand(), 1);
        deployMinefields(deployedMinefields, getLocalPlayer().getNbrMFVibra(),
                         2);

        return deployedMinefields;
    }

    @Override
    protected PlayerIDandList<Coords> calculateArtyAutoHitHexes() {
        final PlayerIDandList<Coords> artyAutoHitHexes = new PlayerIDandList<>();
        artyAutoHitHexes.setPlayerID(getLocalPlayer().getId());
        return artyAutoHitHexes;
    }

    private void deployMinefields(final Vector<Minefield> deployedMinefields,
                                  final int number,
                                  final int type) {
        for (int i = 0; i < number; i++) {
            final Coords coords = new Coords(Compute.randomInt(game.getBoard()
                                                                   .getWidth()),
                                             Compute.randomInt(game.getBoard().getHeight())
            );

            if (game.containsMinefield(coords)) {
                final Minefield mf = game.getMinefields(coords).get(0);
                if (mf.getPlayerId() == getLocalPlayer().getId()) {
                    i--;
                }
            } else {
                Minefield mf = null;

                if (0 == type) {
                    mf = Minefield.createMinefield(coords, getLocalPlayer()
                            .getId(), Minefield.TYPE_CONVENTIONAL, 10);
                } else if (1 == type) {
                    mf = Minefield.createMinefield(coords, getLocalPlayer()
                            .getId(), Minefield.TYPE_COMMAND_DETONATED, 10);
                } else if (2 == type) {
                    mf = Minefield.createMinefield(coords, getLocalPlayer()
                            .getId(), Minefield.TYPE_VIBRABOMB, 20);
                }
                deployedMinefields.add(mf);
            }
        }
    }

    /*
     * Calculate the best location to aim at on a target Mech. Attack options
     * must match 1:1 with WeaponAttackActions in Vector.
     */
    private void getAimPoint(final TreeSet<AttackOption> attack_tree,
                             final Vector<EntityAction> atk_action_list) {

        if ((null == attack_tree) || (null == atk_action_list)) {
            return;
        }

        WeaponAttackAction aimed_attack;
        AttackOption current_option;

        final Vector<Integer> target_id_list; // List of viable aimed-shot targets

        // Adjusted damages
        double base_damage, base_odds;
        double refactored_damage, refactored_head;

        // Armor values
        // Order is: head, ct, lt, rt, la, ra, ll, rl
        final double[] values = { 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0 };

        // Internal structure values
        // Order is: head, ct, lt, rt, la, ra, ll, rl
        final double[] is_values = { 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0 };

        // Fitness values
        // Order is: head, ct, lt, rt, la, ra, ll, rl
        final double[] fitness = { 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0 };

        // Counters for armor penetration
        // Order is: head, ct, lt, rt, la, ra, ll, rl
        final int[] pen_counters = { 0, 0, 0, 0, 0, 0, 0, 0 };

        int attacker_id, test_target;
        int action_index;

        // Base to-hit
        int base_to_hit;

        // Best locations to aim for
        int best_loc, best_loc_head;

        boolean has_tcomp = false;
        boolean imob_target, rear_shot;
        boolean is_primary_target;

        // For each attack action

        target_id_list = new Vector<>();
        for (final EntityAction aea : atk_action_list) {

            if (aea instanceof WeaponAttackAction) {
                // Get the attacker

                attacker_id = atk_action_list.get(0)
                                             .getEntityId();

                // Check to see if the attacker has a tcomp

                has_tcomp = game.getEntity(attacker_id).hasTargComp();

                // Get the target entity id

                test_target = ((WeaponAttackAction) aea).getTargetId();

                // If the target is a Mech

                if (game.getEntity(test_target) instanceof Mech) {

                    // If the target is officially immobile or if the attacker
                    // has a tcomp

                    if ((has_tcomp)
                        | (game.getEntity(test_target).isImmobile())) {
                        if (!target_id_list.contains(test_target)) {
                            target_id_list.add(test_target);
                        }
                    }
                }
            }
        }

        // For each valid target

        is_primary_target = true;
        for (final Integer aTarget_id_list : target_id_list) {

            // Set the current target

            test_target = aTarget_id_list;
            imob_target = game.getEntity(test_target).isImmobile();

            // Get the targets aspect ratio

            rear_shot = false;
            for (final AttackOption anAttack_tree1 : attack_tree) {
                current_option = anAttack_tree1;
                if (current_option.target.getEntity().getId() == test_target) {
                    final int attack_direction = current_option.toHit.getSideTable();
                    rear_shot = ToHitData.SIDE_REAR == attack_direction;
                    break;
                }
            }

            // Get the armor values for the target and make them negative (count
            // up)

            values[0] = game.getEntity(test_target).getArmor(Mech.LOC_HEAD);
            values[1] = game.getEntity(test_target).getArmor(Mech.LOC_CT,
                                                             rear_shot);
            values[2] = game.getEntity(test_target).getArmor(Mech.LOC_LT,
                                                             rear_shot);
            values[3] = game.getEntity(test_target).getArmor(Mech.LOC_RT,
                                                             rear_shot);
            values[4] = game.getEntity(test_target).getArmor(Mech.LOC_LARM);
            values[5] = game.getEntity(test_target).getArmor(Mech.LOC_RARM);
            values[6] = game.getEntity(test_target).getArmor(Mech.LOC_LLEG);
            values[7] = game.getEntity(test_target).getArmor(Mech.LOC_RLEG);

            // Get the internals for the target

            is_values[0] = game.getEntity(test_target).getInternal(
                    Mech.LOC_HEAD);
            is_values[1] = game.getEntity(test_target).getInternal(Mech.LOC_CT);
            is_values[2] = game.getEntity(test_target).getInternal(Mech.LOC_LT);
            is_values[3] = game.getEntity(test_target).getInternal(Mech.LOC_RT);
            is_values[4] = game.getEntity(test_target).getInternal(
                    Mech.LOC_LARM);
            is_values[5] = game.getEntity(test_target).getInternal(
                    Mech.LOC_RARM);
            is_values[6] = game.getEntity(test_target).getInternal(
                    Mech.LOC_LLEG);
            is_values[7] = game.getEntity(test_target).getInternal(
                    Mech.LOC_RLEG);

            // Reset the fitness array
            for (int arr_index = 0; 8 > arr_index; arr_index++) {
                fitness[arr_index] = 0.0;
            }

            // Reset the penetration counter

            for (int arr_index = 0; 8 > arr_index; arr_index++) {
                pen_counters[arr_index] = 0;
            }

            // For each attack option

            action_index = 0;
            refactored_damage = 0.0;
            refactored_head = 0.0;

            best_loc = Mech.LOC_CT;
            best_loc_head = Mech.LOC_CT;
            for (final AttackOption anAttack_tree : attack_tree) {

                // If the target of the attack option is the current target

                current_option = anAttack_tree;
                if (test_target == current_option.target.getEntity().getId()) {

                    // Get the weapon

                    final Mounted test_weapon = current_option.weapon;
                    final boolean aptGunnery = current_option.target.getEntity().getCrew().getOptions()
                                                                    .booleanOption(OptionsConstants.PILOT_APTITUDE_GUNNERY);

                    // If the weapon is not LBX cannon or LBX cannon loaded with
                    // slug

                    boolean direct_fire = true;
                    if (!test_weapon.getType()
                                    .hasFlag(WeaponType.F_DIRECT_FIRE)) {
                        direct_fire = false;
                    }
                    if (test_weapon.getType().hasFlag(WeaponType.F_PULSE)) {
                        direct_fire = false;
                    }
                    if ((AmmoType.T_AC_LBX == ((WeaponType) test_weapon.getType()).getAmmoType())
                        || (AmmoType.T_AC_LBX == ((WeaponType) test_weapon.getType())
                            .getAmmoType())) {
                        if (AmmoType.M_CLUSTER == ((AmmoType) test_weapon.getLinked().getType())
                                .getAmmoType()) {
                            direct_fire = false;
                        }
                    }
                    if (1 < test_weapon.getCurrentShots()) {
                        direct_fire = false;
                    }

                    // If the weapon is direct fire

                    if (direct_fire) {

                        // Get the expected damage, to-hit number, and odds
                        // (0-1) of hitting

                        base_damage = is_primary_target ? current_option.primary_expected
                                                        : current_option.expected;
                        base_to_hit = is_primary_target ? current_option.toHit
                                .getValue()
                                                        : current_option.toHit.getValue() + 1;
                        base_odds = is_primary_target ? current_option.primary_odds
                                                      : current_option.odds;
                        base_damage = 0.0 == base_odds ? 0.0 : base_damage
                                                               / base_odds;

                        // If the target is mobile, only a tcomp can make an
                        // aimed shot

                        if (!imob_target & has_tcomp) {

                            // Refactor the expected damage to account for
                            // increased to-hit number

                            refactored_head = 0.0;
                            if ((12 >= (base_to_hit + 4)) && Compute.allowAimedShotWith(test_weapon,
                                                                                        IAimingModes
                                                                                                .AIM_MODE_TARG_COMP)) {
                                refactored_damage = base_damage
                                                    * (Compute.oddsAbove(base_to_hit + 4, aptGunnery) / 100.0);
                                ((WeaponAttackAction) atk_action_list
                                        .get(action_index))
                                        .setAimingMode(IAimingModes.AIM_MODE_TARG_COMP);
                                // Consider that a regular shot has a roughly
                                // 20% chance of hitting the same location
                                // Use the better of the regular shot or aimed
                                // shot
                                if ((0.2 * base_damage * (Compute.oddsAbove(base_to_hit, aptGunnery) / 100.0)) >
                                    refactored_damage) {
                                    refactored_damage = 0.2
                                                        * base_damage
                                                        * (Compute.oddsAbove(base_to_hit, aptGunnery) / 100.0);
                                    ((WeaponAttackAction) atk_action_list
                                            .get(action_index))
                                            .setAimingMode(IAimingModes.AIM_MODE_NONE);
                                }
                            } else {
                                refactored_damage = 0.0;
                                ((WeaponAttackAction) atk_action_list
                                        .get(action_index))
                                        .setAimingMode(IAimingModes.AIM_MODE_NONE);
                            }

                        }

                        // If the target is immobile, the shot will always be
                        // aimed

                        if (imob_target) {

                            // If the attacker has a tcomp, consider both
                            // options: immobile aim, tcomp aim

                            if (has_tcomp) {

                                if (Compute.allowAimedShotWith(test_weapon, IAimingModes.AIM_MODE_TARG_COMP)) {
                                    // Refactor the expected damage to account for
                                    // increased to-hit number of the tcomp

                                    refactored_damage = base_damage
                                                        * (Compute.oddsAbove(base_to_hit + 4, aptGunnery) / 100.0);
                                    refactored_head = 0.0;
                                    ((WeaponAttackAction) atk_action_list
                                            .get(action_index))
                                            .setAimingMode(IAimingModes.AIM_MODE_TARG_COMP);

                                    // Check against immobile aim mode w/tcomp
                                    // assist

                                }
                                if (((0.50 * base_damage * (Compute
                                                                    .oddsAbove(base_to_hit, aptGunnery) / 100.0)) >
                                     refactored_damage) && Compute.allowAimedShotWith(test_weapon,
                                                                                      IAimingModes.AIM_MODE_IMMOBILE)) {
                                    refactored_damage = 0.50
                                                        * base_damage
                                                        * (Compute.oddsAbove(base_to_hit, aptGunnery) / 100.0);
                                    refactored_head = 0.50
                                                      * base_damage
                                                      * (Compute
                                                                 .oddsAbove(base_to_hit + 7, aptGunnery) / 100.0);
                                    ((WeaponAttackAction) atk_action_list
                                            .get(action_index))
                                            .setAimingMode(IAimingModes.AIM_MODE_IMMOBILE);
                                }

                            } else if (Compute.allowAimedShotWith(test_weapon, IAimingModes.AIM_MODE_IMMOBILE)) {

                                // If the attacker doesn't have a tcomp, settle
                                // for immobile aim

                                refactored_damage = 0.50
                                                    * base_damage
                                                    * (Compute.oddsAbove(base_to_hit, aptGunnery) / 100.0);
                                refactored_head = 0.50
                                                  * base_damage
                                                  * (Compute.oddsAbove(base_to_hit + 7, aptGunnery) / 100.0);
                                ((WeaponAttackAction) atk_action_list
                                        .get(action_index))
                                        .setAimingMode(IAimingModes.AIM_MODE_IMMOBILE);

                            }
                        }

                        // Count the refactored damage off each location. Count
                        // hits to IS.
                        // Ignore locations that have been previously destroyed

                        for (int arr_index = 0; 8 > arr_index; arr_index++) {
                            if (0 == arr_index) {
                                values[arr_index] -= refactored_head;
                            } else {
                                values[arr_index] -= refactored_damage;
                            }
                            if ((0 > values[arr_index])
                                & (0 < is_values[arr_index])) {
                                is_values[arr_index] += values[arr_index];
                                values[arr_index] = 0;
                                pen_counters[arr_index]++;
                            }
                        }
                    }

                    // End if (AttackAction against current target)

                }

                action_index++;

            }

            double loc_mod;
            for (int arr_index = 0; 8 > arr_index; arr_index++) {

                // If any location has had its armor stripped but is not
                // destroyed,
                // criticals may result

                if ((0 >= values[arr_index]) & (0 < is_values[arr_index])) {
                    switch (arr_index) {
                        case 0: // Head hits are very good, pilot damage and
                            // critical systems
                            fitness[arr_index] = 4.0 * pen_counters[arr_index];
                            fitness[arr_index] += getAimModifier(test_target,
                                                                 Mech.LOC_HEAD);
                            break;
                        case 1: // CT hits are good, chances at hitting gyro,
                            // engine
                            fitness[arr_index] = 3.0 * pen_counters[arr_index];
                            fitness[arr_index] += getAimModifier(test_target,
                                                                 Mech.LOC_CT);
                            break;
                        case 2: // Side torso hits are good, equipment hits and
                            // ammo slots
                            loc_mod = getAimModifier(test_target, Mech.LOC_LT);
                            fitness[arr_index] = 2.0 * pen_counters[arr_index];
                            fitness[arr_index] += loc_mod;
                            break;
                        case 3:
                            loc_mod = getAimModifier(test_target, Mech.LOC_RT);
                            fitness[arr_index] = 2.0 * pen_counters[arr_index];
                            fitness[arr_index] += loc_mod;
                            break;
                        case 6: // Leg hits are good, reduces target mobility
                            loc_mod = getAimModifier(test_target, Mech.LOC_LLEG);
                            fitness[arr_index] = 2.0 * pen_counters[arr_index];
                            fitness[arr_index] += loc_mod;
                            break;
                        case 7:
                            loc_mod = getAimModifier(test_target, Mech.LOC_RLEG);
                            fitness[arr_index] = 2.0 * pen_counters[arr_index];
                            fitness[arr_index] += loc_mod;
                            break;
                        case 4: // Arm hits might damage some weapons, but not
                            // the best option
                            loc_mod = getAimModifier(test_target, Mech.LOC_LARM);
                            fitness[arr_index] = pen_counters[arr_index];
                            fitness[arr_index] += loc_mod;
                            break;
                        case 5:
                            loc_mod = getAimModifier(test_target, Mech.LOC_RARM);
                            fitness[arr_index] = pen_counters[arr_index];
                            fitness[arr_index] += loc_mod;
                    }
                }

                // If any location has been destroyed, adjust the location value
                // relative to its value

                if ((0 >= is_values[arr_index]) & (0 < pen_counters[arr_index])) {

                    switch (arr_index) {
                        case 0: // Destroying the head is a hard kill and gets
                            // rid of the pilot, too
                            fitness[arr_index] += 3 * getAimModifier(
                                    test_target, Mech.LOC_HEAD);
                            break;
                        case 1: // Destroying the CT is a hard kill
                            fitness[arr_index] += 2 * getAimModifier(
                                    test_target, Mech.LOC_CT);
                            break;
                        case 2: // Destroying a side torso could be a soft kill
                            // or cripple
                            fitness[arr_index] += 1.5 * getAimModifier(
                                    test_target, Mech.LOC_LT);
                            break;
                        case 3:
                            fitness[arr_index] += 1.5 * getAimModifier(
                                    test_target, Mech.LOC_RT);
                            break;
                        case 6: // Destroying a leg is a mobility kill
                            fitness[arr_index] += 1.5 * getAimModifier(
                                    test_target, Mech.LOC_LLEG);
                            break;
                        case 7:
                            fitness[arr_index] += 1.5 * getAimModifier(
                                    test_target, Mech.LOC_RLEG);
                            break;
                        case 4: // Destroying an arm can cripple a Mech, but not
                            // the best option
                            fitness[arr_index] += getAimModifier(test_target,
                                                                 Mech.LOC_LARM);
                            break;
                        case 5:
                            fitness[arr_index] += getAimModifier(test_target,
                                                                 Mech.LOC_RARM);
                            break;

                    }
                }

            }

            // Get the best target location, including the head

            refactored_damage = fitness[1];
            for (int arr_index = 0; 8 > arr_index; arr_index++) {
                if (fitness[arr_index] > refactored_damage) {
                    refactored_damage = fitness[arr_index];
                    switch (arr_index) {
                        case 0:
                            best_loc_head = Mech.LOC_HEAD;
                            break;
                        case 2: // case 1 is CT, which was initialized as
                            // default
                            best_loc_head = Mech.LOC_LT;
                            break;
                        case 3:
                            best_loc_head = Mech.LOC_RT;
                            break;
                        case 4:
                            best_loc_head = Mech.LOC_LARM;
                            break;
                        case 5:
                            best_loc_head = Mech.LOC_RARM;
                            break;
                        case 6:
                            best_loc_head = Mech.LOC_LLEG;
                            break;
                        case 7:
                            best_loc_head = Mech.LOC_RLEG;
                            break;
                        default:
                            best_loc_head = Mech.LOC_CT;
                    }
                }
            }

            // Get the best target location, not including the head
            int temp_index = 1;
            refactored_damage = fitness[1];
            for (int arr_index = 2; 8 > arr_index; arr_index++) {
                if (fitness[arr_index] > refactored_damage) {
                    refactored_damage = fitness[arr_index];
                    temp_index = arr_index;
                    switch (arr_index) {
                        case 2: // case 1 is CT, which was set as default
                            best_loc = Mech.LOC_LT;
                            break;
                        case 3:
                            best_loc = Mech.LOC_RT;
                            break;
                        case 4:
                            best_loc = Mech.LOC_LARM;
                            break;
                        case 5:
                            best_loc = Mech.LOC_RARM;
                            break;
                        case 6:
                            best_loc = Mech.LOC_LLEG;
                            break;
                        case 7:
                            best_loc = Mech.LOC_RLEG;
                            break;
                        default:
                            best_loc = Mech.LOC_CT;
                    }
                }
            }

            // For all weapon attack actions

            for (final EntityAction entityAction : atk_action_list) {
                aimed_attack = (WeaponAttackAction) entityAction;

                // If the target of the action is the current target

                if (aimed_attack.getTargetId() == test_target) {

                    // If the weapon aim mode is set to use a tcomp

                    if (IAimingModes.AIM_MODE_TARG_COMP == aimed_attack.getAimingMode()) {

                        // If the location is at least close to being breached
                        // or the target is immobile

                        if (values[temp_index] <= Compute.randomInt(5)) {
                            aimed_attack.setAimedLocation(best_loc);
                        } else {
                            aimed_attack
                                    .setAimingMode(IAimingModes.AIM_MODE_NONE);
                            aimed_attack.setAimedLocation(Entity.LOC_NONE);
                        }

                    }

                    // If the weapon aim mode is set for immobile aim

                    if (IAimingModes.AIM_MODE_IMMOBILE == aimed_attack.getAimingMode()) {
                        aimed_attack.setAimedLocation(best_loc_head);
                    }

                }

            }

            // Any targets after this are secondary targets. Use secondary odds
            // and damage.

            is_primary_target = false;
        }
    }

    private double getAimModifier(final int target_id,
                                  final int location) {

        final double loc_total;

        // TODO: change the factor of 0.1 to float depending on critical item
        // type

        loc_total = 0.1 * game.getEntity(target_id).getHittableCriticals(
                location);

        return loc_total;
    }

    @Override
    protected void checkMoral() {
        // unused.
    }
}
