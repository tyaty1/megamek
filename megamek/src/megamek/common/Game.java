/*
 * MegaMek -
 * Copyright (C) 2000,2001,2002,2003,2004,2005 Ben Mazur (bmazur@sev.org)
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

package megamek.common;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.CopyOnWriteArrayList;

import megamek.common.GameTurn.SpecificEntityTurn;
import megamek.common.actions.ArtilleryAttackAction;
import megamek.common.actions.AttackAction;
import megamek.common.actions.EntityAction;
import megamek.common.event.GameBoardChangeEvent;
import megamek.common.event.GameBoardNewEvent;
import megamek.common.event.GameEndEvent;
import megamek.common.event.GameEntityChangeEvent;
import megamek.common.event.GameEntityNewEvent;
import megamek.common.event.GameEntityNewOffboardEvent;
import megamek.common.event.GameEntityRemoveEvent;
import megamek.common.event.GameEvent;
import megamek.common.event.GameListener;
import megamek.common.event.GameNewActionEvent;
import megamek.common.event.GamePhaseChangeEvent;
import megamek.common.event.GamePlayerChangeEvent;
import megamek.common.event.GameSettingsChangeEvent;
import megamek.common.event.GameTurnChangeEvent;
import megamek.common.logging.DefaultMmLogger;
import megamek.common.logging.LogLevel;
import megamek.common.logging.MMLogger;
import megamek.common.options.GameOptions;
import megamek.common.options.OptionsConstants;
import megamek.common.weapons.AttackHandler;
import megamek.server.SmokeCloud;
import megamek.server.victory.Victory;

/**
 * The game class is the root of all data about the game in progress. Both the
 * Client and the Server should have one of these objects and it is their job to
 * keep it synched.
 */
public class Game implements Serializable, IGame {
    /**
     *
     */
    private static final long serialVersionUID = 8376320092671792532L;
    private static final MMLogger LOGGER = DefaultMmLogger.getInstance();

    /**
     * Define constants to describe the condition a unit was in when it wass
     * removed from the game.
     */

    private GameOptions options = new GameOptions();

    public IBoard board = new Board();

    private final List<Entity> entities = new CopyOnWriteArrayList<>();
    private final Hashtable<Integer, Entity> entityIds = new Hashtable<>();

    /**
     * Track entities removed from the game (probably by death)
     */
    private Vector<Entity> vOutOfGame = new Vector<>();

    private final Vector<IPlayer> players = new Vector<>();
    private Vector<Team> teams = new Vector<>(); // DES

    private final Hashtable<Integer, IPlayer> playerIds = new Hashtable<>();

    private final Map<Coords, HashSet<Integer>> entityPosLookup = new HashMap<>();

    /**
     * have the entities been deployed?
     */
    private boolean deploymentComplete = false;

    /**
     * how's the weather?
     */
    private PlanetaryConditions planetaryConditions = new PlanetaryConditions();

    /**
     * what round is it?
     */
    private int roundCount = 0;

    /**
     * The current turn list
     */
    private final Vector<GameTurn> turnVector = new Vector<>();
    private int turnIndex = 0;

    /**
     * The present phase
     */
    private Phase phase = Phase.PHASE_UNKNOWN;

    /**
     * The past phase
     */
    private Phase lastPhase = Phase.PHASE_UNKNOWN;

    // phase state
    private Vector<EntityAction> actions = new Vector<>();
    private final Vector<AttackAction> pendingCharges = new Vector<>();
    private final Vector<AttackAction> pendingRams = new Vector<>();
    private final Vector<AttackAction> pendingTeleMissileAttacks = new Vector<>();
    private final Vector<PilotingRollData> pilotRolls = new Vector<>();
    private final Vector<PilotingRollData> extremeGravityRolls = new Vector<>();
    private final Vector<PilotingRollData> controlRolls = new Vector<>();
    private final Vector<Team> initiativeRerollRequests = new Vector<>();

    // reports
    private final GameReports gameReports = new GameReports();

    private boolean forceVictory = false;
    private int victoryPlayerId = Player.PLAYER_NONE;
    private int victoryTeam = Player.TEAM_NONE;

    private Hashtable<Integer, Vector<Entity>> deploymentTable = new Hashtable<>();
    private int lastDeploymentRound = 0;

    private final Hashtable<Coords, Vector<Minefield>> minefields = new Hashtable<>();
    private final Vector<Minefield> vibrabombs = new Vector<>();
    private Vector<AttackHandler> attacks = new Vector<>();
    private Vector<ArtilleryAttackAction> offboardArtilleryAttacks = new Vector<>();

    private int lastEntityId;

    private final Vector<TagInfo> tagInfoForTurn = new Vector<>();
    private Vector<Flare> flares = new Vector<>();
    private HashSet<Coords> illuminatedPositions =
            new HashSet<>();

    private HashMap<String, Object> victoryContext = null;

    // internal integer value for an external game id link
    private int externalGameId = 0;

    // victory condition related stuff
    private Victory victory = null;

    // smoke clouds
    private final List<SmokeCloud> smokeCloudList = new CopyOnWriteArrayList<>();

    transient private Vector<GameListener> gameListeners = new Vector<>();

    /**
     * Constructor
     */
    public Game() {
        // empty
    }

    // Added public accessors for external game id
    public int getExternalGameId() {
        return externalGameId;
    }

    public void setExternalGameId(final int value) {
        externalGameId = value;
    }

    public IBoard getBoard() {
        return board;
    }

    public void setBoard(final IBoard board) {
        final IBoard oldBoard = this.board;
        this.board = board;
        processGameEvent(new GameBoardNewEvent(this, oldBoard, board));
    }

    public boolean containsMinefield(final Coords coords) {
        return minefields.containsKey(coords);
    }

    public Vector<Minefield> getMinefields(final Coords coords) {
        final Vector<Minefield> mfs = minefields.get(coords);
        if (null == mfs) {
            return new Vector<>();
        }
        return mfs;
    }

    public int getNbrMinefields(final Coords coords) {
        final Vector<Minefield> mfs = minefields.get(coords);
        if (null == mfs) {
            return 0;
        }

        return mfs.size();
    }

    /**
     * Get the coordinates of all mined hexes in the game.
     *
     * @return an <code>Enumeration</code> of the <code>Coords</code> containing
     * minefields. This will not be <code>null</code>.
     */
    public Enumeration<Coords> getMinedCoords() {
        return minefields.keys();
    }

    public void addMinefield(final Minefield mf) {
        addMinefieldHelper(mf);
        processGameEvent(new GameBoardChangeEvent(this));
    }

    public void addMinefields(final Vector<Minefield> mines) {
        for (int i = 0; i < mines.size(); i++) {
            final Minefield mf = mines.elementAt(i);
            addMinefieldHelper(mf);
        }
        processGameEvent(new GameBoardChangeEvent(this));
    }

    public void setMinefields(final Vector<Minefield> minefields) {
        clearMinefieldsHelper();
        for (int i = 0; i < minefields.size(); i++) {
            final Minefield mf = minefields.elementAt(i);
            addMinefieldHelper(mf);
        }
        processGameEvent(new GameBoardChangeEvent(this));
    }

    public void resetMinefieldDensity(final Vector<Minefield> newMinefields) {
        if (1 > newMinefields.size()) {
            return;
        }
        final Vector<Minefield> mfs = minefields.get(newMinefields.firstElement()
                                                                  .getCoords());
        mfs.clear();
        for (int i = 0; i < newMinefields.size(); i++) {
            final Minefield mf = newMinefields.elementAt(i);
            addMinefieldHelper(mf);
        }
        processGameEvent(new GameBoardChangeEvent(this));
    }

    private void addMinefieldHelper(final Minefield mf) {
        Vector<Minefield> mfs = minefields.get(mf.getCoords());
        if (null == mfs) {
            mfs = new Vector<>();
            mfs.addElement(mf);
            minefields.put(mf.getCoords(), mfs);
            return;
        }
        mfs.addElement(mf);
    }

    public void removeMinefield(final Minefield mf) {
        removeMinefieldHelper(mf);
        processGameEvent(new GameBoardChangeEvent(this));
    }

    private void removeMinefieldHelper(final Minefield mf) {
        final Vector<Minefield> mfs = minefields.get(mf.getCoords());
        if (null == mfs) {
            return;
        }

        final Enumeration<Minefield> e = mfs.elements();
        while (e.hasMoreElements()) {
            final Minefield mftemp = e.nextElement();
            if (mftemp.equals(mf)) {
                mfs.removeElement(mftemp);
                break;
            }
        }
        if (mfs.isEmpty()) {
            minefields.remove(mf.getCoords());
        }
    }

    public void clearMinefields() {
        clearMinefieldsHelper();
        processGameEvent(new GameBoardChangeEvent(this));
    }

    private void clearMinefieldsHelper() {
        minefields.clear();
        vibrabombs.removeAllElements();

        final Enumeration<IPlayer> iter = getPlayers();
        while (iter.hasMoreElements()) {
            final IPlayer player = iter.nextElement();
            player.removeMinefields();
        }
    }

    public Vector<Minefield> getVibrabombs() {
        return vibrabombs;
    }

    public void addVibrabomb(final Minefield mf) {
        vibrabombs.addElement(mf);
    }

    public void removeVibrabomb(final Minefield mf) {
        vibrabombs.removeElement(mf);
    }

    public boolean containsVibrabomb(final Minefield mf) {
        return vibrabombs.contains(mf);
    }

    public GameOptions getOptions() {
        return options;
    }

    public void setOptions(final GameOptions options) {
        if (null == options) {
            LOGGER.log(getClass(), "setOptions(GameOptions)",
                       new NullPointerException("Can't set the game options to null!"));
        } else {
            this.options = options;
            processGameEvent(new GameSettingsChangeEvent(this));
        }
    }

    /**
     * Return an enumeration of teams in the game
     */
    public Enumeration<Team> getTeams() {
        return teams.elements();
    }

    /**
     * Return the current number of teams in the game.
     */
    public int getNoOfTeams() {
        return teams.size();
    }

    /**
     * This returns a clone of the vector of teams. Each element is one of the
     * teams in the game.
     */
    public List<Team> getTeamsVector() {
        return Collections.unmodifiableList(teams);
    }

    /**
     * Return a players team Note: may return null if player has no team
     */
    public Team getTeamForPlayer(final IPlayer p) {
        for (final Team team : teams) {
            for (final Enumeration<IPlayer> j = team.getPlayers(); j.hasMoreElements(); ) {
                final IPlayer player = j.nextElement();
                if (p == player) {
                    return team;
                }
            }
        }
        return null;
    }

    /**
     * Set up the teams vector. Each player on a team (Team 1 .. Team X) is
     * placed in the appropriate vector. Any player on 'No Team', is placed in
     * their own object
     */
    public void setupTeams() {
        final Vector<Team> initTeams = new Vector<>();
        final boolean useTeamInit = getOptions().getOption(OptionsConstants.BASE_TEAM_INITIATIVE)
                                                .booleanValue();

        // Get all NO_TEAM players. If team_initiative is false, all
        // players are on their own teams for initiative purposes.
        for (final Enumeration<IPlayer> i = getPlayers(); i.hasMoreElements(); ) {
            final IPlayer player = i.nextElement();
            // Ignore players not on a team
            if (IPlayer.TEAM_UNASSIGNED == player.getTeam()) {
                continue;
            }
            if (!useTeamInit || (IPlayer.TEAM_NONE == player.getTeam())) {
                final Team new_team = new Team(IPlayer.TEAM_NONE);
                new_team.addPlayer(player);
                initTeams.addElement(new_team);
            }
        }

        if (useTeamInit) {
            // Now, go through all the teams, and add the appropriate player
            for (int t = IPlayer.TEAM_NONE + 1; t < IPlayer.MAX_TEAMS; t++) {
                Team new_team = null;
                for (final Enumeration<IPlayer> i = getPlayers(); i.hasMoreElements(); ) {
                    final IPlayer player = i.nextElement();
                    if (player.getTeam() == t) {
                        if (null == new_team) {
                            new_team = new Team(t);
                        }
                        new_team.addPlayer(player);
                    }
                }

                if (null != new_team) {
                    initTeams.addElement(new_team);
                }
            }
        }

        // May need to copy state over from previous teams, such as initiative
        if ((null != teams) && (Phase.PHASE_LOUNGE != getPhase())) {
            for (final Team newTeam : initTeams) {
                for (final Team oldTeam : teams) {
                    if (newTeam.equals(oldTeam)) {
                        newTeam.setInitiative(oldTeam.getInitiative());
                    }
                }
            }
        }
        teams = initTeams;
    }

    /**
     * Return an enumeration of player in the game
     */
    public Enumeration<IPlayer> getPlayers() {
        return players.elements();
    }

    /**
     * Return the players vector
     */
    public Vector<IPlayer> getPlayersVector() {
        return players;
    }

    /**
     * Return the current number of active players in the game.
     */
    public int getNoOfPlayers() {
        return players.size();
    }

    /**
     * Returns the individual player assigned the id parameter.
     */
    public IPlayer getPlayer(final int id) {
        if (IPlayer.PLAYER_NONE == id) {
            return null;
        }
        return playerIds.get(id);
    }

    public void addPlayer(final int id,
                          final IPlayer player) {
        player.setGame(this);
        players.addElement(player);
        playerIds.put(id, player);
        setupTeams();
        updatePlayer(player);
    }

    public void setPlayer(final int id,
                          final IPlayer player) {
        final IPlayer oldPlayer = getPlayer(id);
        player.setGame(this);
        players.setElementAt(player, players.indexOf(oldPlayer));
        playerIds.put(id, player);
        setupTeams();
        updatePlayer(player);
    }

    private void updatePlayer(final IPlayer player) {
        processGameEvent(new GamePlayerChangeEvent(this, player));
    }

    public void removePlayer(final int id) {
        final IPlayer playerToRemove = getPlayer(id);
        players.removeElement(playerToRemove);
        playerIds.remove(id);
        setupTeams();
        processGameEvent(new GamePlayerChangeEvent(this, playerToRemove));
    }

    /**
     * Returns the number of entities owned by the player, regardless of their
     * status, as long as they are in the game.
     */
    public int getEntitiesOwnedBy(final IPlayer player) {
        int count = 0;
        for (final Entity entity : entities) {
            if (entity.getOwner().equals(player)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the number of entities owned by the player, regardless of their
     * status.
     */
    public int getAllEntitiesOwnedBy(final IPlayer player) {
        int count = 0;
        for (final Entity entity : entities) {
            if (entity.getOwner().equals(player)) {
                count++;
            }
        }
        for (final Entity entity : vOutOfGame) {
            if (entity.getOwner().equals(player)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the number of non-destroyed entityes owned by the player
     */
    public int getLiveEntitiesOwnedBy(final IPlayer player) {
        int count = 0;
        for (final Entity entity : entities) {
            if (entity.getOwner().equals(player) && !entity.isDestroyed()
                    && !entity.isCarcass()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the number of non-destroyed entities owned by the player,
     * including entities not yet deployed. Ignore offboard units and captured
     * Mek pilots.
     */
    public int getLiveDeployedEntitiesOwnedBy(final IPlayer player) {
        int count = 0;
        for (final Entity entity : entities) {
            if (entity.getOwner().equals(player) && !entity.isDestroyed()
                && !entity.isCarcass()
                && !entity.isOffBoard() && !entity.isCaptured()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the number of non-destroyed deployed entities owned by the
     * player. Ignore offboard units and captured Mek pilots.
     */
    public int getLiveCommandersOwnedBy(final IPlayer player) {
        int count = 0;
        for (final Entity entity : entities) {
            if (entity.getOwner().equals(player) && !entity.isDestroyed()
                && !entity.isCarcass()
                && entity.isCommander() && !entity.isOffBoard()
                && !entity.isCaptured()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns true if the player has a valid unit with the Tactical Genius
     * pilot special ability.
     */
    public boolean hasTacticalGenius(final IPlayer player) {
        for (final Entity entity : entities) {
            if (entity.getCrew().getOptions().booleanOption(OptionsConstants.MISC_TACTICAL_GENIUS)
                    && entity.getOwner().equals(player) && !entity.isDestroyed() && entity.isDeployed()
                    && !entity.isCarcass() && !entity.getCrew().isUnconscious()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get a vector of entity objects that are "acceptable" to attack with this
     * entity
     */
    public List<Entity> getValidTargets(final Entity entity) {
        final List<Entity> ents = new ArrayList<>();

        final boolean friendlyFire = getOptions().booleanOption(OptionsConstants.BASE_FRIENDLY_FIRE);

        for (final Entity otherEntity : entities) {
            // Even if friendly fire is acceptable, do not shoot yourself
            // Enemy units not on the board can not be shot.
            if ((null != otherEntity.getPosition())
                && !otherEntity.isOffBoard()
                && otherEntity.isTargetable()
                && !otherEntity.isSensorReturn(entity.getOwner())
                && otherEntity.hasSeenEntity(entity.getOwner())
                && (entity.isEnemyOf(otherEntity) || (friendlyFire && (entity
                            .getId() != otherEntity.getId())))) {
                // Air to Ground - target must be on flight path
                if (Compute.isAirToGround(entity, otherEntity)) {
                    if (entity.getPassedThrough().contains(
                            otherEntity.getPosition())) {
                        ents.add(otherEntity);
                    }                
                } else {
                    ents.add(otherEntity);
                }
            }
        }

        return Collections.unmodifiableList(ents);
    }

    /**
     * Returns true if this phase has turns. If false, the phase is simply
     * waiting for everybody to declare "done".
     */
    public boolean phaseHasTurns(final IGame.Phase thisPhase) {
        switch (thisPhase) {
            case PHASE_SET_ARTYAUTOHITHEXES:
            case PHASE_DEPLOY_MINEFIELDS:
            case PHASE_DEPLOYMENT:
            case PHASE_MOVEMENT:
            case PHASE_FIRING:
            case PHASE_PHYSICAL:
            case PHASE_TARGETING:
            case PHASE_OFFBOARD:
                return true;
            default:
                return false;
        }
    }

    public boolean isPhaseSimultaneous() {
        return phase.isPhaseSimultaneous(this);
    }

    /**
     * Returns the current GameTurn object
     */
    public GameTurn getTurn() {
        if ((0 > turnIndex) || (turnIndex >= turnVector.size())) {
            return null;
        }
        return turnVector.elementAt(turnIndex);
    }

    public GameTurn getTurnForPlayer(final int pn) {
        for (int i = turnIndex; i < turnVector.size(); i++) {
            final GameTurn gt = turnVector.get(i);
            if (gt.isValid(pn, this)) {
                return gt;
            }
        }
        return null;
    }

    /**
     * Changes to the next turn, returning it.
     */
    public GameTurn changeToNextTurn() {
        turnIndex++;
        return getTurn();
    }

    /**
     * Resets the turn index to -1 (awaiting first turn)
     */
    public void resetTurnIndex() {
        turnIndex = -1;
    }

    /**
     * Returns true if there is a turn after the current one
     */
    public boolean hasMoreTurns() {
        return turnVector.size() > (turnIndex + 1);
    }

    /**
     * Inserts a turn that will come directly after the current one
     */
    public void insertNextTurn(final GameTurn turn) {
        turnVector.insertElementAt(turn, turnIndex + 1);
    }

    /**
     * Inserts a turn after the specific index
     */
    public void insertTurnAfter(final GameTurn turn,
                                final int index) {
        if ((index + 1) >= turnVector.size()) {
            turnVector.add(turn);
        } else {
            turnVector.insertElementAt(turn, index + 1);
        }
    }

    public void swapTurnOrder(final int index1,
                              final int index2) {
        final GameTurn turn1 = turnVector.get(index1);
        final GameTurn turn2 = turnVector.get(index2);
        turnVector.set(index2, turn1);
        turnVector.set(index1, turn2);
    }

    /**
     * Returns an Enumeration of the current turn list
     */
    public Enumeration<GameTurn> getTurns() {
        return turnVector.elements();
    }

    /**
     * Returns the current turn index
     */
    public int getTurnIndex() {
        return turnIndex;
    }

    /**
     * Sets the current turn index
     */
    public void setTurnIndex(final int turnIndex,
                             final int prevPlayerId) {
        // FIXME: occasionally getTurn() returns null. Handle that case
        // intelligently.
        this.turnIndex = turnIndex;
        processGameEvent(new GameTurnChangeEvent(this, getPlayer(getTurn()
                .getPlayerNum()), prevPlayerId));
    }

    /**
     * Returns the current turn vector
     */
    public List<GameTurn> getTurnVector() {
        return Collections.unmodifiableList(turnVector);
    }

    /**
     * Sets the current turn vector
     */
    public void setTurnVector(final List<GameTurn> turnVector) {
        this.turnVector.clear();
        this.turnVector.addAll(turnVector);
    }

    public Phase getPhase() {
        return phase;
    }

    public void setPhase(final Phase phase) {
        final Phase oldPhase = this.phase;
        this.phase = phase;
        // Handle phase-specific items.
        switch (phase) {
            case PHASE_LOUNGE:
                reset();
                break;
            case PHASE_TARGETING:
                resetActions();
                break;
            case PHASE_MOVEMENT:
                resetActions();
                break;
            case PHASE_FIRING:
                resetActions();
                break;
            case PHASE_PHYSICAL:
                resetActions();
                break;
            case PHASE_DEPLOYMENT:
                resetActions();
                break;
            case PHASE_INITIATIVE:
                resetActions();
                resetCharges();
                resetRams();
                break;
            // TODO Is there better solution to handle charges?
            case PHASE_PHYSICAL_REPORT:
            case PHASE_END:
                resetCharges();
                resetRams();
                break;
            default:
        }

        processGameEvent(new GamePhaseChangeEvent(this, oldPhase, phase));
    }

    public Phase getLastPhase() {
        return lastPhase;
    }

    public void setLastPhase(final Phase lastPhase) {
        this.lastPhase = lastPhase;
    }

    public void setDeploymentComplete(final boolean deploymentComplete) {
        this.deploymentComplete = deploymentComplete;
    }

    public boolean isDeploymentComplete() {
        return deploymentComplete;
    }

    /**
     * Sets up up the hashtable of who deploys when
     */
    public void setupRoundDeployment() {
        deploymentTable = new Hashtable<>();

        for (final Entity ent : entities) {
            if (ent.isDeployed()) {
                continue;
            }

            Vector<Entity> roundVec = deploymentTable.get(ent.getDeployRound());

            if (null == roundVec) {
                roundVec = new Vector<>();
                deploymentTable.put(ent.getDeployRound(), roundVec);
            }

            roundVec.addElement(ent);
            lastDeploymentRound = Math.max(lastDeploymentRound,
                                           ent.getDeployRound());
        }
    }

    /**
     * Checks to see if we've past our deployment completion
     */
    public void checkForCompleteDeployment() {
        setDeploymentComplete(lastDeploymentRound < getRoundCount());
    }

    /**
     * Check to see if we should deploy this round
     */
    public boolean shouldDeployThisRound() {
        return shouldDeployForRound(getRoundCount());
    }

    public boolean shouldDeployForRound(final int round) {
        final Vector<Entity> vec = getEntitiesToDeployForRound(round);

        return ((null != vec) && (0 != vec.size()));
    }

    private Vector<Entity> getEntitiesToDeployForRound(final int round) {
        return deploymentTable.get(round);
    }

    /**
     * Clear this round from this list of entities to deploy
     */
    public void clearDeploymentThisRound() {
        deploymentTable.remove(getRoundCount());
    }

    /**
     * Returns a vector of entities that have not yet deployed
     */
    public List<Entity> getUndeployedEntities() {
        final List<Entity> entList = new ArrayList<>();
        final Enumeration<Vector<Entity>> iter = deploymentTable.elements();

        while (iter.hasMoreElements()) {
            final Vector<Entity> vecTemp = iter.nextElement();

            for (int i = 0; i < vecTemp.size(); i++) {
                entList.add(vecTemp.elementAt(i));
            }
        }

        return Collections.unmodifiableList(entList);
    }

    /**
     * Returns an enumeration of all the entites in the game.
     */
    public Iterator<Entity> getEntities() {
        return entities.iterator();
    }

    public Entity getPreviousEntityFromList(final Entity current) {
        if ((null != current) && entities.contains(current)) {
            int prev = entities.indexOf(current) - 1;
            if (0 > prev) {
                prev = entities.size() - 1; // wrap around to end
            }
            return entities.get(prev);
        }
        return null;
    }

    public Entity getNextEntityFromList(final Entity current) {
        if ((null != current) && entities.contains(current)) {
            int next = entities.indexOf(current) + 1;
            if (next >= entities.size()) {
                next = 0; // wrap-around to begining
            }
            return entities.get(next);
        }
        return null;
    }

    /**
     * Returns the actual vector for the entities
     */
    public List<Entity> getEntitiesVector() {
        return Collections.unmodifiableList(entities);
    }

    public synchronized void setEntitiesVector(final List<Entity> entities) {
        //checkPositionCacheConsistency();
        this.entities.clear();
        this.entities.addAll(entities);
        reindexEntities();
        resetEntityPositionLookup();
        processGameEvent(new GameEntityNewEvent(this, entities));
    }

    /**
     * Returns the actual vector for the out-of-game entities
     */
    public Vector<Entity> getOutOfGameEntitiesVector() {
        return vOutOfGame;
    }

    /**
     * Swap out the current list of dead (or fled) units for a new one.
     *
     * @param vOutOfGame - the new <code>Vector</code> of dead or fled units. This
     *                   value should <em>not</em> be <code>null</code>.
     * @throws IllegalArgumentException if the new list is <code>null</code>.
     */
    public void setOutOfGameEntitiesVector(final List<Entity> vOutOfGame) {
        assert (null != vOutOfGame) : "New out-of-game list should not be null.";
        final Vector<Entity> newOutOfGame = new Vector<>();

        // Add entities for the existing players to the game.
        for (final Entity entity : vOutOfGame) {
            final int ownerId = entity.getOwnerId();
            if ((Entity.NONE != ownerId) && (null != getPlayer(ownerId))) {
                entity.setGame(this);
                newOutOfGame.addElement(entity);
            }
        }
        this.vOutOfGame = newOutOfGame;
        processGameEvent(new GameEntityNewOffboardEvent(this));
    }

    /**
     * Returns an out-of-game entity.
     *
     * @param id the <code>int</code> ID of the out-of-game entity.
     * @return the out-of-game <code>Entity</code> with that ID. If no
     * out-of-game entity has that ID, returns a <code>null</code>.
     */
    public Entity getOutOfGameEntity(final int id) {
        Entity match = null;
        final Enumeration<Entity> iter = vOutOfGame.elements();
        while ((null == match) && iter.hasMoreElements()) {
            final Entity entity = iter.nextElement();
            if (id == entity.getId()) {
                match = entity;
            }
        }
        return match;
    }

    /**
     * Returns a <code>Vector</code> containing the <code>Entity</code>s that
     * are in the same C3 network as the passed-in unit. The output will contain
     * the passed-in unit, if the unit has a C3 computer. If the unit has no C3
     * computer, the output will be empty (but it will never be
     * <code>null</code>).
     *
     * @param entity - the <code>Entity</code> whose C3 network co- members is
     *               required. This value may be <code>null</code>.
     * @return a <code>Vector</code> that will contain all other
     * <code>Entity</code>s that are in the same C3 network as the
     * passed-in unit. This <code>Vector</code> may be empty, but it
     * will not be <code>null</code>.
     * @see #getC3SubNetworkMembers(Entity)
     */
    public Vector<Entity> getC3NetworkMembers(final Entity entity) {
        final Vector<Entity> members = new Vector<>();
        //WOR
        // Does the unit have a C3 computer?
        if ((null != entity) && (entity.hasC3() || entity.hasC3i() || entity.hasActiveNovaCEWS() || entity.hasNavalC3())) {

            // Walk throught the entities in the game, and add all
            // members of the C3 network to the output Vector.
            for (final Entity unit : entities) {
                if (entity.equals(unit) || entity.onSameC3NetworkAs(unit)) {
                    members.addElement(unit);
                }
            }

        } // End entity-has-C3

        return members;
    }

    /**
     * Returns a <code>Vector</code> containing the <code>Entity</code>s that
     * are in the C3 sub-network under the passed-in unit. The output will
     * contain the passed-in unit, if the unit has a C3 computer. If the unit
     * has no C3 computer, the output will be empty (but it will never be
     * <code>null</code>). If the passed-in unit is a company commander or a
     * member of a C3i network, this call is the same as
     * <code>getC3NetworkMembers</code>.
     *
     * @param entity - the <code>Entity</code> whose C3 network sub- members is
     *               required. This value may be <code>null</code>.
     * @return a <code>Vector</code> that will contain all other
     * <code>Entity</code>s that are in the same C3 network under the
     * passed-in unit. This <code>Vector</code> may be empty, but it
     * will not be <code>null</code>.
     * @see #getC3NetworkMembers(Entity)
     */
    public Vector<Entity> getC3SubNetworkMembers(final Entity entity) {
        //WOR
        // Handle null, C3i, NC3, and company commander units.
        if ((null == entity) || entity.hasC3i() || entity.hasNavalC3() || entity.hasActiveNovaCEWS() || entity.C3MasterIs(
                entity)) {
            return getC3NetworkMembers(entity);
        }

        final Vector<Entity> members = new Vector<>();

        // Does the unit have a C3 computer?
        if (entity.hasC3()) {

            // Walk throught the entities in the game, and add all
            // sub-members of the C3 network to the output Vector.
            for (final Entity unit : entities) {
                if (entity.equals(unit) || unit.C3MasterIs(entity)) {
                    members.addElement(unit);
                }
            }

        } // End entity-has-C3

        return members;
    }

    /**
     * Returns a <code>Hashtable</code> that maps the <code>Coords</code> of
     * each unit in this <code>Game</code> to a <code>Vector</code> of
     * <code>Entity</code>s at that positions. Units that have no position (e.g.
     * loaded units) will not be in the map.
     *
     * @return a <code>Hashtable</code> that maps the <code>Coords</code>
     * positions or each unit in the game to a <code>Vector</code> of
     * <code>Entity</code>s at that position.
     */
    public Hashtable<Coords, Vector<Entity>> getPositionMap() {
        final Hashtable<Coords, Vector<Entity>> positionMap = new Hashtable<>();
        Vector<Entity> atPos;

        // Walk through the entities in this game.
        for (final Entity entity : entities) {
            // Get the vector for this entity's position.
            final Coords coords = entity.getPosition();
            if (null != coords) {
                atPos = positionMap.get(coords);

                // If this is the first entity at this position,
                // create the vector and add it to the map.
                if (null == atPos) {
                    atPos = new Vector<>();
                    positionMap.put(coords, atPos);
                }

                // Add the entity to the vector for this position.
                atPos.addElement(entity);

            }
        } // Handle the next entity.

        // Return the map.
        return positionMap;
    }

    /**
     * Returns an enumeration of salvagable entities.
     */
    public Enumeration<Entity> getGraveyardEntities() {
        final Vector<Entity> graveyard = new Vector<>();

        for (final Entity entity : vOutOfGame) {
            if ((IEntityRemovalConditions.REMOVE_SALVAGEABLE == entity.getRemovalCondition())
                || (IEntityRemovalConditions.REMOVE_EJECTED == entity.getRemovalCondition())) {
                graveyard.addElement(entity);
            }
        }

        return graveyard.elements();
    }

    /**
     * Returns an enumeration of wrecked entities.
     */
    public Enumeration<Entity> getWreckedEntities() {
        final Vector<Entity> wrecks = new Vector<>();
        for (final Entity entity : vOutOfGame) {
            if ((IEntityRemovalConditions.REMOVE_SALVAGEABLE == entity.getRemovalCondition())
                || (IEntityRemovalConditions.REMOVE_EJECTED == entity.getRemovalCondition())
                || (IEntityRemovalConditions.REMOVE_DEVASTATED == entity.getRemovalCondition())) {
                wrecks.addElement(entity);
            }
        }
        
        return wrecks.elements();
    }

    /**
     * Returns an enumeration of entities that have retreated
     */
 // TODO: Correctly implement "Captured" Entities
    public Enumeration<Entity> getRetreatedEntities() {
        final Vector<Entity> sanctuary = new Vector<>();

        for (final Entity entity : vOutOfGame) {
            if ((IEntityRemovalConditions.REMOVE_IN_RETREAT == entity.getRemovalCondition())
                || (IEntityRemovalConditions.REMOVE_CAPTURED == entity.getRemovalCondition())
                || (IEntityRemovalConditions.REMOVE_PUSHED == entity.getRemovalCondition())) {
                sanctuary.addElement(entity);
            }
        }

        return sanctuary.elements();
    }

    /**
     * Returns an enumeration of entities that were utterly destroyed
     */
    public Enumeration<Entity> getDevastatedEntities() {
        final Vector<Entity> smithereens = new Vector<>();

        for (final Entity entity : vOutOfGame) {
            if (IEntityRemovalConditions.REMOVE_DEVASTATED == entity.getRemovalCondition()) {
                smithereens.addElement(entity);
            }
        }

        return smithereens.elements();
    }
    
    /**
     * Returns an enumeration of "carcass" entities, i.e., vehicles with dead
     * crews that are still on the map.
     */
    public Enumeration<Entity> getCarcassEntities() {
        final Vector<Entity> carcasses = new Vector<>();

        for (final Entity entity : entities) {
            if (entity.isCarcass()) {
                carcasses.addElement(entity);
            }
        }
        
        return carcasses.elements();
    }

    /**
     * Return the current number of entities in the game.
     */
    public int getNoOfEntities() {
        return entities.size();
    }

    /**
     * Returns the appropriate target for this game given a type and id
     */
    public Targetable getTarget(final int nType,
                                final int nID) {
        try {
            switch (nType) {
                case Targetable.TYPE_ENTITY:
                    return getEntity(nID);
                case Targetable.TYPE_HEX_CLEAR:
                case Targetable.TYPE_HEX_IGNITE:
                case Targetable.TYPE_HEX_BOMB:
                case Targetable.TYPE_MINEFIELD_DELIVER:
                case Targetable.TYPE_FLARE_DELIVER:
                case Targetable.TYPE_HEX_EXTINGUISH:
                case Targetable.TYPE_HEX_ARTILLERY:
                case Targetable.TYPE_HEX_SCREEN:
                case Targetable.TYPE_HEX_AERO_BOMB:
                case Targetable.TYPE_HEX_TAG:
                    return new HexTarget(HexTarget.idToCoords(nID), board,
                                         nType);
                case Targetable.TYPE_FUEL_TANK:
                case Targetable.TYPE_FUEL_TANK_IGNITE:
                case Targetable.TYPE_BUILDING:
                case Targetable.TYPE_BLDG_IGNITE:
                case Targetable.TYPE_BLDG_TAG:
                    return new BuildingTarget(BuildingTarget.idToCoords(nID),
                                              board, nType);
                case Targetable.TYPE_MINEFIELD_CLEAR:
                    return new MinefieldTarget(MinefieldTarget.idToCoords(nID),
                                               board);
                case Targetable.TYPE_INARC_POD:
                    return INarcPod.idToInstance(nID);
                default:
                    return null;
            }
        } catch (final IllegalArgumentException t) {
            return null;
        }
    }

    /**
     * Returns the entity with the given id number, if any.
     */

    public Entity getEntity(final int id) {
        return entityIds.get(id);
    }

    /**
     * looks for an entity by id number even if out of the game
     */
    public Entity getEntityFromAllSources(final int id) {
        final Entity en = getEntity(id);
        if(null == en) {
            for (final Entity entity : vOutOfGame) {
                if(entity.getId() == id) {
                    return entity;
                }
            }
        }
        return en;
    }

    public void addEntities(final List<Entity> entities) {
        for (final Entity entity : entities) {
            addEntity(entity, false);
        }
        processGameEvent(new GameEntityNewEvent(this, entities));
    }

    public void addEntity(final int id,
                          final Entity entity) {
        // Disregard the passed id, addEntity(Entity) pulls the id from the
        //  Entity instance.
        addEntity(entity);
    }

    public void addEntity(final Entity entity) {
        addEntity(entity, true);
    }

    public synchronized void addEntity(final Entity entity,
                                       final boolean genEvent) {
        entity.setGame(this);
        if (entity instanceof Mech) {
            ((Mech) entity).setBAGrabBars();
        }
        if (entity instanceof Tank) {
            ((Tank) entity).setBAGrabBars();
        }

        // Add magnetic clamp mounts
        if ((entity instanceof Mech) && !entity.isOmni()
                && !entity.hasBattleArmorHandles()) {
            entity.addTransporter(new ClampMountMech());
        } else if ((entity instanceof Tank) && !entity.isOmni()
                && !entity.hasBattleArmorHandles()) {
            entity.addTransporter(new ClampMountTank());
        }

        entity.setGameOptions();
        if (null == entity.getC3UUIDAsString()) { // We don't want to be
            // resetting a UUID that
            // exists already!
            entity.setC3UUID();
        }
        // Add this Entity, ensuring that it's id is unique
        int id = entity.getId();
        if (!entityIds.containsKey(id)) {
            entityIds.put(id, entity);
        } else {
            id = getNextEntityId();
            entity.setId(id);
            entityIds.put(id, entity);
        }
        entities.add(entity);
        updateEntityPositionLookup(entity, null);

        if (id > lastEntityId) {
            lastEntityId = id;
        }

        // And... lets get this straight now.
        if ((entity instanceof Mech)
            && getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)) {
            ((Mech) entity).setAutoEject(true);
            if (entity.hasCase()
                || ((Mech) entity).hasCASEIIAnywhere()) {
                ((Mech) entity).setCondEjectAmmo(false);
            } else {
                ((Mech) entity).setCondEjectAmmo(true);
            }
            ((Mech) entity).setCondEjectEngine(true);
            ((Mech) entity).setCondEjectCTDest(true);
            ((Mech) entity).setCondEjectHeadshot(true);
        }

        entity.setInitialBV(entity.calculateBattleValue(false, false));

        assert (entities.size() == entityIds.size()) : "Add Entity failed";
        if (genEvent) {
            processGameEvent(new GameEntityNewEvent(this, entity));
        }
    }

    public void setEntity(final int id,
                          final Entity entity) {
        setEntity(id, entity, null);
    }

    public synchronized void setEntity(final int id,
                                       final Entity entity,
                                       final Vector<UnitLocation> movePath) {
        final Entity oldEntity = getEntity(id);
        if (null == oldEntity) {
            addEntity(entity);
        } else {
            entity.setGame(this);
            entities.set(entities.indexOf(oldEntity), entity);
            entityIds.put(id, entity);
            // Get the collection of positions
            final HashSet<Coords> oldPositions = oldEntity.getOccupiedCoords();
            // Update position lookup table
            updateEntityPositionLookup(entity, oldPositions);

            // Not sure if this really required
            if (id > lastEntityId) {
                lastEntityId = id;
            }

            processGameEvent(
                    new GameEntityChangeEvent(this, entity, movePath, oldEntity));
        }
        assert (entities.size() == entityIds.size()) : "Set Entity Failed";
    }

    /**
     * @return int containing an unused entity id
     */
    public int getNextEntityId() {
        return lastEntityId + 1;
    }

    /**
     * Returns true if an entity with the specified id number exists in this
     * game.
     */
    public boolean hasEntity(final int entityId) {
        return entityIds.containsKey(entityId);
    }

    /**
     * Remove an entity from the master list. If we can't find that entity,
     * (probably due to double-blind) ignore it.
     */
    public synchronized void removeEntity(final int id,
                                          final int condition) {
        final Entity toRemove = getEntity(id);
        if (null == toRemove) {
            // This next statement has been cluttering up double-blind
            // logs for quite a while now. I'm assuming it's no longer
            // useful.
            // System.err.println("Game#removeEntity: could not find entity to
            // remove");
            return;
        }

        entities.remove(toRemove);
        entityIds.remove(id);
        removeEntityPositionLookup(toRemove);

        toRemove.setRemovalCondition(condition);

        // do not keep never-joined entities
        if ((null != vOutOfGame)
            && (IEntityRemovalConditions.REMOVE_NEVER_JOINED != condition)) {
            vOutOfGame.addElement(toRemove);
        }

        // We also need to remove it from the list of things to be deployed...
        // we might still be in this list if we never joined the game
        if (0 < deploymentTable.size()) {
            final Enumeration<Vector<Entity>> iter = deploymentTable.elements();

            while (iter.hasMoreElements()) {
                final Vector<Entity> vec = iter.nextElement();

                for (int i = vec.size() - 1; 0 <= i; i--) {
                    final Entity en = vec.elementAt(i);

                    if (en.getId() == id) {
                        vec.removeElementAt(i);
                    }
                }
            }
        }
        processGameEvent(new GameEntityRemoveEvent(this, toRemove));
    }

    public void removeEntities(final List<Integer> ids,
                               final int condition) {
        for (final Integer id : ids) {
            removeEntity(id, condition);
        }
    }

    /**
     * Resets this game.
     */
    public synchronized void reset() {
        roundCount = 0;

        entities.clear();
        entityIds.clear();
        entityPosLookup.clear();

        vOutOfGame.removeAllElements();

        turnVector.clear();
        turnIndex = 0;

        resetActions();
        resetCharges();
        resetRams();
        resetPSRs();
        resetArtilleryAttacks();
        resetAttacks();
        // removeMinefields();  Broken and bad!
        clearMinefields();
        removeArtyAutoHitHexes();
        flares.removeAllElements();
        illuminatedPositions.clear();
        clearAllReports();
        smokeCloudList.clear();

        forceVictory = false;
        victoryPlayerId = Player.PLAYER_NONE;
        victoryTeam = Player.TEAM_NONE;
        lastEntityId = 0;
        planetaryConditions = new PlanetaryConditions();
    }

    private void removeArtyAutoHitHexes() {
        final Enumeration<IPlayer> iter = getPlayers();
        while (iter.hasMoreElements()) {
            final IPlayer player = iter.nextElement();
            player.removeArtyAutoHitHexes();
        }
    }

//    private void removeMinefields() {
//        minefields.clear();
//        vibrabombs.removeAllElements();
//
//        Enumeration<IPlayer> iter = getPlayers();
//        while (iter.hasMoreElements()) {
//            IPlayer player = iter.nextElement();
//            player.removeMinefields();
//        }
//    }

    /**
     * Regenerates the entities by id hashtable by going thru all entities in
     * the Vector
     */
    private void reindexEntities() {
        entityIds.clear();
        lastEntityId = 0;

        // Add these entities to the game.
        for (final Entity entity : entities) {
            final int id = entity.getId();
            entityIds.put(id, entity);

            if (id > lastEntityId) {
                lastEntityId = id;
            }
        }
        // We need to ensure that each entity has the propery Game reference
        //  however, the entityIds Hashmap must be fully formed before this
        //  is called, since setGame also calls setGame for loaded Entities
        for (final Entity entity : entities) {
            entity.setGame(this);
        }
    }

    /**
     * Returns the first entity at the given coordinate, if any. Only returns
     * targetable (non-dead) entities.
     *
     * @param c the coordinates to search at
     */
    public Entity getFirstEntity(final Coords c) {
        for (final Entity entity : entities) {
            if (c.equals(entity.getPosition()) && entity.isTargetable()) {
                return entity;
            }
        }
        return null;
    }

    /**
     * Returns the first enemy entity at the given coordinate, if any. Only
     * returns targetable (non-dead) entities.
     *
     * @param c             the coordinates to search at
     * @param currentEntity the entity that is firing
     */
    public Entity getFirstEnemyEntity(final Coords c,
                                      final Entity currentEntity) {
        for (final Entity entity : entities) {
            if (c.equals(entity.getPosition()) && entity.isTargetable()
                && entity.isEnemyOf(currentEntity)) {
                return entity;
            }
        }
        return null;
    }

    /**
     * Returns an Enumeration of the active entities at the given coordinates.
     */
    public Iterator<Entity> getEntities(final Coords c) {
        return getEntities(c, false);
    }

    /**
     * Returns an Enumeration of the active entities at the given coordinates.
     */
    public Iterator<Entity> getEntities(final Coords c,
                                        final boolean ignore) {
        return getEntitiesVector(c,ignore).iterator();
    }

    /**
     * Return a List of Entities at Coords <code>c</code>
     *
     * @param c The coordinates to check
     * @return <code>List<Entity></code>
     */
    public List<Entity> getEntitiesVector(final Coords c) {
        return getEntitiesVector(c, false);
    }

    /**
     * Return a List of Entities at Coords <code>c</code>
     *
     * @param c The coordinates to check
     * @param ignore
     *            Flag that determines whether the ability to target is ignored
     * @return <code>List<Entity></code>
     */
    public synchronized List<Entity> getEntitiesVector(final Coords c,
                                                       final boolean ignore) {
        //checkPositionCacheConsistency();
        // Make sure the look-up is initialized
        if (1 > entityPosLookup.size() && 0 < entities.size()) {
            resetEntityPositionLookup();
        }
        final Set<Integer> posEntities = entityPosLookup.get(c);
        final List<Entity> vector = new ArrayList<>();
        if (null != posEntities) {
            for (final Integer eId : posEntities) {
                final Entity e = getEntity(eId);
                
                // if the entity with the given ID doesn't exist, we will update the lookup table
                // and move on
                if (null == e) {
                    posEntities.remove(eId);
                    continue;
                }
                
                if (e.isTargetable() || ignore) {
                    vector.add(e);

                    // Sanity check
                    final HashSet<Coords> positions = e.getOccupiedCoords();
                    if (!positions.contains(c)) {
                        LOGGER.log(getClass(), "getEntitiesVector(Coords, ignore)",
                                   new NoSuchElementException(e.getDisplayName() + " is not in " + c + "!"));
                    }
                }
            }
        }
        return Collections.unmodifiableList(vector);
    }

    /**
     * Return a Vector of gun emplacements at Coords <code>c</code>
     *
     * @param c The coordinates to check
     * @return <code>Vector<Entity></code>
     */
    public Vector<GunEmplacement> getGunEmplacements(final Coords c) {
        final Vector<GunEmplacement> vector = new Vector<>();

        // Only build the list if the coords are on the board.
        if (board.contains(c)) {
            for (final Entity entity : getEntitiesVector(c, true)) {
                if (entity.hasETypeFlag(Entity.ETYPE_GUN_EMPLACEMENT)) {
                    vector.addElement((GunEmplacement) entity);
                }
            }
        }

        return vector;
    }
    
    /**
     * Determine if the given set of coordinates has a gun emplacement on the roof of a building.
     * @param c The coordinates to check
     */
    public boolean hasRooftopGunEmplacement(final Coords c) {
        final Building building = getBoard().getBuildingAt(c);
        if (null == building) {
            return false;
        }

        final IHex hex = getBoard().getHex(c);

        for (final Entity entity : getEntitiesVector(c, true)) {
            if (entity.hasETypeFlag(Entity.ETYPE_GUN_EMPLACEMENT) && entity.getElevation() == hex.ceiling()) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * Returns a Target for an Accidental Fall From above, or null if no
     * possible target is there
     *
     * @param c      The <code>Coords</code> of the hex in which the accidental
     *               fall from above happens
     * @param ignore The entity who is falling
     * @return The <code>Entity</code> that should be an AFFA target.
     */
    public Entity getAffaTarget(final Coords c,
                                final Entity ignore) {
        final Vector<Entity> vector = new Vector<>();
        if (board.contains(c)) {
            final IHex hex = board.getHex(c);
            for (final Entity entity : getEntitiesVector(c)) {
                if (entity.isTargetable()
                    && ((0 == entity.getElevation()) // Standing on hex surface 
                        || (entity.getElevation() == -hex.depth())) // Standing on hex floor
                    && (0 == entity.getAltitude())
                    && !(entity instanceof Infantry) && (entity != ignore)) {
                    vector.addElement(entity);
                }
            }
        }
        if (!vector.isEmpty()) {
            final int count = vector.size();
            final int random = Compute.randomInt(count);
            return vector.elementAt(random);
        }
        return null;
    }

    /**
     * Returns an <code>Enumeration</code> of the enemy's active entities at the
     * given coordinates.
     *
     * @param c
     *            the <code>Coords</code> of the hex being examined.
     * @param currentEntity
     *            the <code>Entity</code> whose enemies are needed.
     * @return an <code>Enumeration</code> of <code>Entity</code>s at the given
     *         coordinates who are enemies of the given unit.
     */
    public Iterator<Entity> getEnemyEntities(final Coords c,
            final Entity currentEntity) {
        // Use an EntitySelector to avoid walking the entities vector twice.
        return getSelectedEntities(new EntitySelector() {
            private final Coords coords = c;
            private final Entity friendly = currentEntity;

            public boolean accept(final Entity entity) {
                return coords.equals(entity.getPosition())
                       && entity.isTargetable() && entity.isEnemyOf(friendly);
            }
        });
    }
    
    /**
     * Returns an <code>Enumeration</code> of all active enemy entities.
     *
     * @param currentEntity
     *            the <code>Entity</code> whose enemies are needed.
     * @return an <code>Enumeration</code> of <code>Entity</code>s at the given
     *         coordinates who are enemies of the given unit.
     */
    public Iterator<Entity> getAllEnemyEntities(final Entity currentEntity) {
        return getSelectedEntities(new EntitySelector() {
            private final Entity friendly = currentEntity;

            public boolean accept(final Entity entity) {
                return entity.isTargetable() && entity.isEnemyOf(friendly);
            }
        });
    }

    /**
     * Returns an <code>Enumeration</code> of friendly active entities at the
     * given coordinates.
     *
     * @param c
     *            the <code>Coords</code> of the hex being examined.
     * @param currentEntity
     *            the <code>Entity</code> whose friends are needed.
     * @return an <code>Enumeration</code> of <code>Entity</code>s at the given
     *         coordinates who are friends of the given unit.
     */
    public Iterator<Entity> getFriendlyEntities(final Coords c,
            final Entity currentEntity) {
        // Use an EntitySelector to avoid walking the entities vector twice.
        return getSelectedEntities(new EntitySelector() {
            private final Coords coords = c;
            private final Entity friendly = currentEntity;

            public boolean accept(final Entity entity) {
                return coords.equals(entity.getPosition())
                       && entity.isTargetable() && !entity.isEnemyOf(friendly);
            }
        });
    }

    /**
     * Moves an entity into the graveyard so it stops getting sent out every
     * phase.
     */
    public void moveToGraveyard(final int id) {
        removeEntity(id, IEntityRemovalConditions.REMOVE_SALVAGEABLE);
    }

    /**
     * See if the <code>Entity</code> with the given ID is out of the game.
     *
     * @param id - the ID of the <code>Entity</code> to be checked.
     * @return <code>true</code> if the <code>Entity</code> is in the graveyard,
     * <code>false</code> otherwise.
     */
    public boolean isOutOfGame(final int id) {
        for (final Entity entity : vOutOfGame) {
            if (entity.getId() == id) {
                return true;
            }
        }

        return false;
    }

    /**
     * See if the <code>Entity</code> is out of the game.
     *
     * @param entity - the <code>Entity</code> to be checked.
     * @return <code>true</code> if the <code>Entity</code> is in the graveyard,
     * <code>false</code> otherwise.
     */
    public boolean isOutOfGame(final Entity entity) {
        return isOutOfGame(entity.getId());
    }

    /**
     * Returns the first entity that can act in the present turn, or null if
     * none can.
     */
    public Entity getFirstEntity() {
        return getFirstEntity(getTurn());
    }

    /**
     * Returns the first entity that can act in the specified turn, or null if
     * none can.33
     */
    public Entity getFirstEntity(final GameTurn turn) {
        return getEntity(getFirstEntityNum(turn));
    }

    /**
     * Returns the id of the first entity that can act in the current turn, or
     * -1 if none can.
     */
    public int getFirstEntityNum() {
        return getFirstEntityNum(getTurn());
    }

    /**
     * Returns the id of the first entity that can act in the specified turn, or
     * -1 if none can.
     */
    public int getFirstEntityNum(final GameTurn turn) {
        if (null == turn) {
            return -1;
        }
        for (final Entity entity : entities) {
            if (turn.isValidEntity(entity, this)) {
                return entity.getId();
            }
        }
        return -1;
    }

    /**
     * Returns the next selectable entity that can act this turn, or null if
     * none can.
     *
     * @param start the index number to start at (not an Entity Id)
     */
    public Entity getNextEntity(int start) {
        if (0 == entities.size()) {
            return null;
        }
        start = start % entities.size();
        final int entityId = entities.get(start).getId();
        return getEntity(getNextEntityNum(getTurn(), entityId));
    }

    /**
     * Returns the entity id of the next entity that can move during the
     * specified
     *
     * @param turn  the turn to use
     * @param start the entity id to start at
     */
    public int getNextEntityNum(final GameTurn turn,
                                final int start) {
        // If we don't have a turn, return ENTITY_NONE
        if (null == turn) {
            return Entity.NONE;
        }
        boolean hasLooped = false;
        int i = (entities.indexOf(entityIds.get(start)) + 1) % entities.size();
        if (-1 == i) {
            //This means we were given an invalid entity ID, punt
            return Entity.NONE;
        }
        final int startingIndex = i;
        while (!((hasLooped) && (i == startingIndex))) {
            final Entity entity = entities.get(i);
            if (turn.isValidEntity(entity, this)) {
                return entity.getId();
            }
            i++;
            if (i == entities.size()) {
                i = 0;
                hasLooped = true;
            }
        }
        // return getFirstEntityNum(turn);
        return Entity.NONE;
    }

    /**
     * Returns the entity id of the previous entity that can move during the
     * specified
     *
     * @param turn  the turn to use
     * @param start the entity id to start at
     */
    public int getPrevEntityNum(final GameTurn turn,
                                final int start) {
        boolean hasLooped = false;
        int i = (entities.indexOf(entityIds.get(start)) - 1) % entities.size();
        if (-2 == i) {
            //This means we were given an invalid entity ID, punt
            return -1;
        }
        if (-1 == i) {
            //This means we were given an invalid entity ID, punt
            i = entities.size() - 1;
        }
        final int startingIndex = i;
        while (!((hasLooped) && (i == startingIndex))) {
            final Entity entity = entities.get(i);
            if (turn.isValidEntity(entity, this)) {
                return entity.getId();
            }
            i--;
            if (0 > i) {
                i = entities.size() - 1;
                hasLooped = true;
            }
        }
        // return getFirstEntityNum(turn);
        return -1;
    }

    public int getFirstDeployableEntityNum(final GameTurn turn) {
        // Repeat the logic from getFirstEntityNum.
        if (null == turn) {
            return -1;
        }
        for (final Entity entity : entities) {
            if (turn.isValidEntity(entity, this)
                && entity.shouldDeploy(getRoundCount())) {
                return entity.getId();
            }
        }
        return -1;
    }

    public int getNextDeployableEntityNum(final GameTurn turn,
                                          final int start) {
        if (0 <= start) {
            for (int i = start; i < entities.size(); i++) {
                final Entity entity = entities.get(i);
                if (turn.isValidEntity(entity, this)
                    && entity.shouldDeploy(getRoundCount())) {
                    return entity.getId();
                }
            }
        }
        return getFirstDeployableEntityNum(turn);
    }

    /**
     * Get the entities for the player.
     *
     * @param player - the <code>Player</code> whose entities are required.
     * @param hide   - should fighters loaded into squadrons be excluded?
     * @return a <code>Vector</code> of <code>Entity</code>s.
     */
    public ArrayList<Entity> getPlayerEntities(final IPlayer player,
                                               final boolean hide) {
        final ArrayList<Entity> output = new ArrayList<>();
        for (final Entity entity : entities) {
            if (entity.isPartOfFighterSquadron() && hide) {
                continue;
            }
            if (player.equals(entity.getOwner())) {
                output.add(entity);
            }
        }
        return output;
    }

    /**
     * Get the entities for the player.
     *
     * @param player - the <code>Player</code> whose entities are required.
     * @param hide   - should fighters loaded into squadrons be excluded from this list?
     * @return a <code>Vector</code> of <code>Entity</code>s.
     */
    public ArrayList<Integer> getPlayerEntityIds(final IPlayer player,
                                                 final boolean hide) {
        final ArrayList<Integer> output = new ArrayList<>();
        for (final Entity entity : entities) {
            if (entity.isPartOfFighterSquadron() && hide) {
                continue;
            }
            if (player.equals(entity.getOwner())) {
                output.add(entity.getId());
            }
        }
        return output;
    }

    /**
     * Determines if the indicated entity is stranded on a transport that can't
     * move.
     * <p/>
     * According to <a href=
     * "http://www.classicbattletech.com/w3t/showflat.php?Cat=&Board=ask&Number=555466&page=2&view=collapsed&sb=5&o=0&fpart="
     * > Randall Bills</a>, the "minimum move" rule allow stranded units to
     * dismount at the start of the turn.
     *
     * @param entity the <code>Entity</code> that may be stranded
     * @return <code>true</code> if the entity is stranded <code>false</code>
     * otherwise.
     */
    public boolean isEntityStranded(final Entity entity) {

        // Is the entity being transported?
        final int transportId = entity.getTransportId();
        final Entity transport = getEntity(transportId);
        if ((Entity.NONE != transportId) && (null != transport)) {

            // aero units don't count here
            if (transport instanceof Aero) {
                return false;
            }

            // Can that transport unload the unit?
            return transport.isImmobile() || (0 == transport.getWalkMP());
        }
        return false;
    }

    /**
     * Returns the number of remaining selectable infantry owned by a player.
     */
    public int getInfantryLeft(final int playerId) {
        final IPlayer player = getPlayer(playerId);
        int remaining = 0;

        for (final Entity entity : entities) {
            if (player.equals(entity.getOwner())
                && entity.isSelectableThisTurn()
                && (entity instanceof Infantry)) {
                remaining++;
            }
        }

        return remaining;
    }

    /**
     * Returns the number of remaining selectable Protomechs owned by a player.
     */
    public int getProtomechsLeft(final int playerId) {
        final IPlayer player = getPlayer(playerId);
        int remaining = 0;

        for (final Entity entity : entities) {
            if (player.equals(entity.getOwner())
                && entity.isSelectableThisTurn()
                && (entity instanceof Protomech)) {
                remaining++;
            }
        }

        return remaining;
    }

    /**
     * Returns the number of Vehicles that <code>playerId</code> has not moved
     * yet this turn.
     *
     * @param playerId
     * @return number of vehicles <code>playerId</code> has not moved yet this
     * turn
     */
    public int getVehiclesLeft(final int playerId) {
        final IPlayer player = getPlayer(playerId);
        int remaining = 0;

        for (final Entity entity : entities) {
            if (player.equals(entity.getOwner())
                && entity.isSelectableThisTurn()
                && (entity instanceof Tank)) {
                remaining++;
            }
        }

        return remaining;
    }

    /**
     * Returns the number of Mechs that <code>playerId</code> has not moved
     * yet this turn.
     *
     * @param playerId
     * @return number of vehicles <code>playerId</code> has not moved yet this
     * turn
     */
    public int getMechsLeft(final int playerId) {
        final IPlayer player = getPlayer(playerId);
        int remaining = 0;

        for (final Entity entity : entities) {
            if (player.equals(entity.getOwner())
                && entity.isSelectableThisTurn()
                && (entity instanceof Mech)) {
                remaining++;
            }
        }

        return remaining;
    }

    /**
     * Removes the first turn found that the specified entity can move in. Used
     * when a turn is played out of order
     */
    public GameTurn removeFirstTurnFor(final Entity entity) {
        assert (Phase.PHASE_MOVEMENT != phase); // special move multi cases
        // ignored
        for (int i = turnIndex; i < turnVector.size(); i++) {
            final GameTurn turn = turnVector.elementAt(i);
            if (turn.isValidEntity(entity, this)) {
                turnVector.removeElementAt(i);
                return turn;
            }
        }
        return null;
    }

    /**
     * Removes the last, next turn found that the specified entity can move in.
     * Used when, say, an entity dies mid-phase.
     */
    public void removeTurnFor(final Entity entity) {
        if (0 == turnVector.size()) {
            return;
        }
        // If the game option "move multiple infantry per mech" is selected,
        // then we might not need to remove a turn at all.
        // A turn only needs to be removed when going from 4 inf (2 turns) to
        // 3 inf (1 turn)
        if (getOptions().booleanOption(OptionsConstants.INIT_INF_MOVE_MULTI)
            && (entity instanceof Infantry)
            && (Phase.PHASE_MOVEMENT == phase)) {
            if (1 != (getInfantryLeft(entity.getOwnerId()) % getOptions().intOption(
                    OptionsConstants.INIT_INF_PROTO_MOVE_MULTI))) {
                // exception, if the _next_ turn is an infantry turn, remove
                // that
                // contrived, but may come up e.g. one inf accidently kills
                // another
                if (hasMoreTurns()) {
                    final GameTurn nextTurn = turnVector.elementAt(turnIndex + 1);
                    if (nextTurn instanceof GameTurn.EntityClassTurn) {
                        final GameTurn.EntityClassTurn ect =
                                (GameTurn.EntityClassTurn) nextTurn;
                        if (ect.isValidClass(GameTurn.CLASS_INFANTRY)
                            && !ect.isValidClass(~GameTurn.CLASS_INFANTRY)) {
                            turnVector.removeElementAt(turnIndex + 1);
                        }
                    }
                }
                return;
            }
        }
        // Same thing but for protos
        if (getOptions().booleanOption(OptionsConstants.INIT_PROTOS_MOVE_MULTI)
            && (entity instanceof Protomech)
            && (Phase.PHASE_MOVEMENT == phase)) {
            if (1 != (getProtomechsLeft(entity.getOwnerId()) % getOptions()
                    .intOption(OptionsConstants.INIT_INF_PROTO_MOVE_MULTI))) {
                // exception, if the _next_ turn is an protomek turn, remove
                // that
                // contrived, but may come up e.g. one inf accidently kills
                // another
                if (hasMoreTurns()) {
                    final GameTurn nextTurn = turnVector.elementAt(turnIndex + 1);
                    if (nextTurn instanceof GameTurn.EntityClassTurn) {
                        final GameTurn.EntityClassTurn ect =
                                (GameTurn.EntityClassTurn) nextTurn;
                        if (ect.isValidClass(GameTurn.CLASS_PROTOMECH)
                            && !ect.isValidClass(~GameTurn.CLASS_PROTOMECH)) {
                            turnVector.removeElementAt(turnIndex + 1);
                        }
                    }
                }
                return;
            }
        }

        // Same thing but for vehicles
        if (getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_VEHICLE_LANCE_MOVEMENT)
            && (entity instanceof Tank) && (Phase.PHASE_MOVEMENT == phase)) {
            if (1 != (getVehiclesLeft(entity.getOwnerId()) % getOptions()
                    .intOption(OptionsConstants.ADVGRNDMOV_VEHICLE_LANCE_MOVEMENT_NUMBER))) {
                // exception, if the _next_ turn is a tank turn, remove that
                // contrived, but may come up e.g. one tank accidently kills
                // another
                if (hasMoreTurns()) {
                    final GameTurn nextTurn = turnVector.elementAt(turnIndex + 1);
                    if (nextTurn instanceof GameTurn.EntityClassTurn) {
                        final GameTurn.EntityClassTurn ect =
                                (GameTurn.EntityClassTurn) nextTurn;
                        if (ect.isValidClass(GameTurn.CLASS_TANK)
                            && !ect.isValidClass(~GameTurn.CLASS_TANK)) {
                            turnVector.removeElementAt(turnIndex + 1);
                        }
                    }
                }
                return;
            }
        }

        // Same thing but for meks
        if (getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_MEK_LANCE_MOVEMENT)
            && (entity instanceof Mech) && (Phase.PHASE_MOVEMENT == phase)) {
            if (1 != (getMechsLeft(entity.getOwnerId()) % getOptions()
                    .intOption(OptionsConstants.ADVGRNDMOV_MEK_LANCE_MOVEMENT_NUMBER))) {
                // exception, if the _next_ turn is a mech turn, remove that
                // contrived, but may come up e.g. one mech accidently kills
                // another
                if (hasMoreTurns()) {
                    final GameTurn nextTurn = turnVector.elementAt(turnIndex + 1);
                    if (nextTurn instanceof GameTurn.EntityClassTurn) {
                        final GameTurn.EntityClassTurn ect =
                                (GameTurn.EntityClassTurn) nextTurn;
                        if (ect.isValidClass(GameTurn.CLASS_MECH)
                            && !ect.isValidClass(~GameTurn.CLASS_MECH)) {
                            turnVector.removeElementAt(turnIndex + 1);
                        }
                    }
                }
                return;
            }
        }


        boolean useInfantryMoveLaterCheck = true;
        // If we have the "infantry move later" or "protos move later" optional
        //  rules, then we may be removing an infantry unit that would be
        //  considered invalid unless we don't consider the extra validity
        //  checks.
        if ((getOptions().booleanOption(OptionsConstants.INIT_INF_MOVE_LATER) &&
             (entity instanceof Infantry)) ||
            (getOptions().booleanOption(OptionsConstants.INIT_PROTOS_MOVE_LATER) &&
             (entity instanceof Protomech))) {
            useInfantryMoveLaterCheck = false;
        }

        for (int i = turnVector.size() - 1; i >= turnIndex; i--) {
            final GameTurn turn = turnVector.elementAt(i);

            if (turn.isValidEntity(entity, this, useInfantryMoveLaterCheck)) {
                turnVector.removeElementAt(i);
                break;
            }
        }
    }

    public int removeSpecificEntityTurnsFor(final Entity entity) {
        final List<GameTurn> turnsToRemove = new ArrayList<>();

        for (final GameTurn turn : turnVector) {
            if (turn instanceof SpecificEntityTurn) {
                final int turnOwner = ((SpecificEntityTurn) turn).getEntityNum();
                if (entity.getId() == turnOwner) {
                    turnsToRemove.add(turn);
                }
            }
        }
        turnVector.removeAll(turnsToRemove);
        return turnsToRemove.size();
    }

    /*
     * Check each player for the presence of a Battle Armor squad equipped with
     * a Magnetic Clamp. If one unit is found, update that player's units to
     * allow the squad to be transported.
     * <p/>
     * This method should be called </b>*ONCE*</b> per game, after all units for
     * all players have been loaded.
     *
     * @return <code>true</code> if a unit was updated, <code>false</code> if no
     *         player has a Battle Armor squad equipped with a Magnetic Clamp.
     */
    /* Taharqa: I am removing this function and instead I am simply adding clamp mounts to all
     * non omni/ none BA handled mechs in the game.addEntity routine - It should not be too much memory to
     * do this and it allows us to load these units in the lobby
    public boolean checkForMagneticClamp() {

        // Declare local variables.
        Player player = null;
        Entity unit = null;
        boolean result;
        Hashtable<Player, Boolean> playerFlags = null;

        // Assume that we don't need new transporters.
        result = false;

        // Create a map of flags for the players.
        playerFlags = new Hashtable<Player, Boolean>(getNoOfPlayers());

        // Walk through the game's entities.
        for (Enumeration<Entity> i = entities.elements(); i.hasMoreElements();) {

            // Is the next unit a Battle Armor squad?
            unit = i.nextElement();
            if (unit instanceof BattleArmor) {

                if (unit.countWorkingMisc(MiscType.F_MAGNETIC_CLAMP) > 0) {
                    // The unit's player needs new transporters.
                    result = true;
                    playerFlags.put(unit.getOwner(), Boolean.TRUE);

                }

            } // End unit-is-BattleArmor

        } // Handle the next entity.

        // Do we need to add any Magnetic Clamp transporters?
        if (result) {

            // Walk through the game's entities again.
            for (Enumeration<Entity> i = entities.elements(); i
                    .hasMoreElements();) {

                // Get this unit's player.
                unit = i.nextElement();
                player = unit.getOwner();

                // Does this player need updated transporters?
                if (Boolean.TRUE.equals(playerFlags.get(player))) {

                    // Add the appropriate transporter to the unit.
                    if (!unit.isOmni() && !unit.hasBattleArmorHandles() && (unit instanceof Mech)) {
                        unit.addTransporter(new ClampMountMech());
                    } else if (!unit.isOmni() && !unit.hasBattleArmorHandles() && (unit instanceof Tank)
                            && !(unit instanceof VTOL)) {
                        unit.addTransporter(new ClampMountTank());
                    }

                }
            } // End player-needs-transports

        } // Handle the next unit.

        // Return the result.
        return result;

    } // End private boolean checkForMagneticClamp()
     */

    /**
     * Adds the specified action to the actions list for this phase.
     */
    public void addAction(final EntityAction ea) {
        actions.addElement(ea);
        processGameEvent(new GameNewActionEvent(this, ea));
    }

    public void setArtilleryVector(final Vector<ArtilleryAttackAction> v) {
        offboardArtilleryAttacks = v;
        processGameEvent(new GameBoardChangeEvent(this));
    }

    private void resetArtilleryAttacks() {
        offboardArtilleryAttacks.removeAllElements();
    }

    public Enumeration<ArtilleryAttackAction> getArtilleryAttacks() {
        return offboardArtilleryAttacks.elements();
    }

    public int getArtillerySize() {
        return offboardArtilleryAttacks.size();
    }

    /**
     * Returns an Enumeration of actions scheduled for this phase.
     */
    public Enumeration<EntityAction> getActions() {
        return actions.elements();
    }

    /**
     * Resets the actions list.
     */
    public void resetActions() {
        actions.removeAllElements();
    }

    /**
     * Removes all actions by the specified entity
     */
    public void removeActionsFor(final int entityId) {
        // or rather, only keeps actions NOT by that entity
        final Vector<EntityAction> toKeep = new Vector<>(actions.size());
        for (final EntityAction ea : actions) {
            if (ea.getEntityId() != entityId) {
                toKeep.addElement(ea);
            }
        }
        actions = toKeep;
    }

    /**
     * Remove a specified action
     *
     * @param o The action to remove.
     */
    public void removeAction(final Object o) {
        actions.removeElement(o);
    }

    public int actionsSize() {
        return actions.size();
    }

    /**
     * Returns the actions vector. Do not use to modify the actions; I will be
     * angry. >:[ Used for sending all actions to the client.
     */
    public List<EntityAction> getActionsVector() {
        return Collections.unmodifiableList(actions);
    }

    public void addInitiativeRerollRequest(final Team t) {
        initiativeRerollRequests.addElement(t);
    }

    public void rollInitAndResolveTies() {
        if (getOptions().booleanOption(OptionsConstants.RPG_INDIVIDUAL_INITIATIVE)) {
            final Vector<TurnOrdered> vRerolls = new Vector<>();
            for (final Entity e : entities) {
                if (initiativeRerollRequests.contains(getTeamForPlayer(e.getOwner()))) {
                    vRerolls.add(e);
                }
            }
            TurnOrdered.rollInitAndResolveTies(getEntitiesVector(), vRerolls, false);
        } else {
            TurnOrdered.rollInitAndResolveTies(teams, initiativeRerollRequests,
                    getOptions().booleanOption(OptionsConstants.INIT_INITIATIVE_STREAK_COMPENSATION));
        }
        initiativeRerollRequests.removeAllElements();

    }
    
    public void handleInitiativeCompensation() {
        if (getOptions().booleanOption(OptionsConstants.INIT_INITIATIVE_STREAK_COMPENSATION)) {
            TurnOrdered.resetInitiativeCompensation(teams, getOptions().booleanOption(OptionsConstants.INIT_INITIATIVE_STREAK_COMPENSATION));
        }
    }

    public int getNoOfInitiativeRerollRequests() {
        return initiativeRerollRequests.size();
    }

    /**
     * Adds a pending displacement attack to the list for this phase.
     */
    public void addCharge(final AttackAction ea) {
        pendingCharges.addElement(ea);
        processGameEvent(new GameNewActionEvent(this, ea));
    }

    /**
     * Returns an Enumeration of displacement attacks scheduled for the end of
     * the physical phase.
     */
    public Enumeration<AttackAction> getCharges() {
        return pendingCharges.elements();
    }

    /**
     * Resets the pending charges list.
     */
    public void resetCharges() {
        pendingCharges.removeAllElements();
    }

    /**
     * Returns the charges vector. Do not modify. >:[ Used for sending all
     * charges to the client.
     */
    public List<AttackAction> getChargesVector() {
        return Collections.unmodifiableList(pendingCharges);
    }

    /**
     * Adds a pending ramming attack to the list for this phase.
     */
    public void addRam(final AttackAction ea) {
        pendingRams.addElement(ea);
        processGameEvent(new GameNewActionEvent(this, ea));
    }

    /**
     * Returns an Enumeration of ramming attacks scheduled for the end of the
     * physical phase.
     */
    public Enumeration<AttackAction> getRams() {
        return pendingRams.elements();
    }

    /**
     * Resets the pending rams list.
     */
    public void resetRams() {
        pendingRams.removeAllElements();
    }

    /**
     * Returns the rams vector. Do not modify. >:[ Used for sending all charges
     * to the client.
     */
    public List<AttackAction> getRamsVector() {
        return Collections.unmodifiableList(pendingRams);
    }

    /**
     * Adds a pending ramming attack to the list for this phase.
     */
    public void addTeleMissileAttack(final AttackAction ea) {
        pendingTeleMissileAttacks.addElement(ea);
        processGameEvent(new GameNewActionEvent(this, ea));
    }

    /**
     * Returns an Enumeration of ramming attacks scheduled for the end of the
     * physical phase.
     */
    public Enumeration<AttackAction> getTeleMissileAttacks() {
        return pendingTeleMissileAttacks.elements();
    }

    /**
     * Resets the pending rams list.
     */
    public void resetTeleMissileAttacks() {
        pendingTeleMissileAttacks.removeAllElements();
    }

    /**
     * Returns the rams vector. Do not modify. >:[ Used for sending all charges
     * to the client.
     */
    public List<AttackAction> getTeleMissileAttacksVector() {
        return Collections.unmodifiableList(pendingTeleMissileAttacks);
    }

    /**
     * Adds a pending PSR to the list for this phase.
     */
    public void addPSR(final PilotingRollData psr) {
        pilotRolls.addElement(psr);
    }

    /**
     * Returns an Enumeration of pending PSRs.
     */
    public Enumeration<PilotingRollData> getPSRs() {
        return pilotRolls.elements();
    }

    /**
     * Adds a pending extreme Gravity PSR to the list for this phase.
     */
    public void addExtremeGravityPSR(final PilotingRollData psr) {
        extremeGravityRolls.addElement(psr);
    }

    /**
     * Returns an Enumeration of pending extreme GravityPSRs.
     */
    public Enumeration<PilotingRollData> getExtremeGravityPSRs() {
        return extremeGravityRolls.elements();
    }

    /**
     * Resets the PSR list for a given entity.
     */
    public void resetPSRs(final Entity entity) {
        PilotingRollData roll;
        final Vector<Integer> rollsToRemove = new Vector<>();
        int i;

        // first, find all the rolls belonging to the target entity
        for (i = 0; i < pilotRolls.size(); i++) {
            roll = pilotRolls.elementAt(i);
            if (roll.getEntityId() == entity.getId()) {
                rollsToRemove.addElement(i);
            }
        }

        // now, clear them out
        for (i = rollsToRemove.size() - 1; -1 < i; i--) {
            pilotRolls.removeElementAt(rollsToRemove.elementAt(i));
        }
    }

    /**
     * Resets the extreme Gravity PSR list.
     */
    public void resetExtremeGravityPSRs() {
        extremeGravityRolls.removeAllElements();
    }

    /**
     * Resets the extreme Gravity PSR list for a given entity.
     */
    public void resetExtremeGravityPSRs(final Entity entity) {
        PilotingRollData roll;
        final Vector<Integer> rollsToRemove = new Vector<>();
        int i;

        // first, find all the rolls belonging to the target entity
        for (i = 0; i < extremeGravityRolls.size(); i++) {
            roll = extremeGravityRolls.elementAt(i);
            if (roll.getEntityId() == entity.getId()) {
                rollsToRemove.addElement(i);
            }
        }

        // now, clear them out
        for (i = rollsToRemove.size() - 1; -1 < i; i--) {
            extremeGravityRolls.removeElementAt(rollsToRemove.elementAt(i));
        }
    }

    /**
     * Resets the PSR list.
     */
    public void resetPSRs() {
        pilotRolls.removeAllElements();
    }

    /**
     * add an AttackHandler to the attacks list
     *
     * @param ah - The <code>AttackHandler</code> to add
     */
    public void addAttack(final AttackHandler ah) {
        attacks.add(ah);
    }

    /**
     * remove an AttackHandler from the attacks list
     *
     * @param ah - The <code>AttackHandler</code> to remove
     */
    public void removeAttack(final AttackHandler ah) {
        attacks.removeElement(ah);
    }

    /**
     * get the attacks
     *
     * @return a <code>Enumeration</code> of all <code>AttackHandler</code>s
     */
    public Enumeration<AttackHandler> getAttacks() {
        return attacks.elements();
    }

    /**
     * get the attacks vector
     *
     * @return the <code>Vector</code> containing the attacks
     */
    public Vector<AttackHandler> getAttacksVector() {
        return attacks;
    }

    /**
     * reset the attacks vector
     */
    public void resetAttacks() {
        attacks = new Vector<>();
    }

    /**
     * set the attacks vector
     *
     * @param v - the <code>Vector</code> that should be the new attacks
     *          vector
     */
    public void setAttacksVector(final Vector<AttackHandler> v) {
        attacks = v;
    }

    /**
     * Getter for property roundCount.
     *
     * @return Value of property roundCount.
     */
    public int getRoundCount() {
        return roundCount;
    }

    public void setRoundCount(final int roundCount) {
        this.roundCount = roundCount;
    }

    /**
     * Increments the round counter
     */
    public void incrementRoundCount() {
        roundCount++;
    }

    /**
     * Getter for property forceVictory.
     *
     * @return Value of property forceVictory.
     */
    public boolean isForceVictory() {
        return forceVictory;
    }

    /**
     * Setter for property forceVictory.
     *
     * @param forceVictory New value of property forceVictory.
     */
    public void setForceVictory(final boolean forceVictory) {
        this.forceVictory = forceVictory;
    }

    public void addReports(final Vector<Report> v) {
        if (0 == v.size()) {
            return;
        }
        gameReports.add(roundCount, v);
    }

    public Vector<Report> getReports(final int r) {
        return gameReports.get(r);
    }

    public Vector<Vector<Report>> getAllReports() {
        return gameReports.get();
    }

    public void setAllReports(final Vector<Vector<Report>> v) {
        gameReports.set(v);
    }

    public void clearAllReports() {
        gameReports.clear();
    }

    public void end(final int winner,
                    final int winnerTeam) {
        setVictoryPlayerId(winner);
        setVictoryTeam(winnerTeam);
        processGameEvent(new GameEndEvent(this));

    }

    /**
     * Getter for property victoryPlayerId.
     *
     * @return Value of property victoryPlayerId.
     */
    public int getVictoryPlayerId() {
        return victoryPlayerId;
    }

    /**
     * Setter for property victoryPlayerId.
     *
     * @param victoryPlayerId New value of property victoryPlayerId.
     */
    public void setVictoryPlayerId(final int victoryPlayerId) {
        this.victoryPlayerId = victoryPlayerId;
    }

    /**
     * Getter for property victoryTeam.
     *
     * @return Value of property victoryTeam.
     */
    public int getVictoryTeam() {
        return victoryTeam;
    }

    /**
     * Setter for property victoryTeam.
     *
     * @param victoryTeam New value of property victoryTeam.
     */
    public void setVictoryTeam(final int victoryTeam) {
        this.victoryTeam = victoryTeam;
    }

    /**
     * Returns true if the specified player is either the victor, or is on the
     * winning team. Best to call during PHASE_VICTORY.
     */
    public boolean isPlayerVictor(final IPlayer player) {
        if (IPlayer.TEAM_NONE == player.getTeam()) {
            return player.getId() == victoryPlayerId;
        }
        return player.getTeam() == victoryTeam;
    }

    public HashMap<String, Object> getVictoryContext() {
        return victoryContext;
    }

    public void setVictoryContext(final HashMap<String, Object> ctx) {
        victoryContext = ctx;
    }

    /**
     * Shortcut to isPlayerVictor(Player player)
     */
    public boolean isPlayerVictor(final int playerId) {
        return isPlayerVictor(getPlayer(playerId));
    }

    /**
     * Get all <code>Entity</code>s that pass the given selection criteria.
     *
     * @param selector the <code>EntitySelector</code> that implements test that an
     *                 entity must pass to be included. This value may be
     *                 <code>null</code> (in which case all entities in the game will
     *                 be returned).
     * @return an <code>Enumeration</code> of all entities that the selector
     * accepts. This value will not be <code>null</code> but it may be
     * empty.
     */
    public Iterator<Entity> getSelectedEntities(final EntitySelector selector) {
        final Iterator<Entity> retVal;

        // If no selector was supplied, return all entities.
        if (null == selector) {
            retVal = this.getEntities();
        }

        // Otherwise, return an anonymous Enumeration
        // that selects entities in this game.
        else {
            retVal = new Iterator<Entity>() {
                private final EntitySelector entitySelector = selector;
                private Entity current = null;
                private final Iterator<Entity> iter = getEntities();

                // Do any more entities meet the selection criteria?
                public boolean hasNext() {
                    // See if we have a pre-approved entity.
                    if (null == current) {

                        // Find the first acceptable entity
                        while ((null == current) && iter.hasNext()) {
                            current = iter.next();
                            if (!entitySelector.accept(current)) {
                                current = null;
                            }
                        }
                    }
                    return (null != current);
                }

                // Get the next entity that meets the selection criteria.
                public Entity next() {
                    // Pre-approve an entity.
                    if (!hasNext()) {
                        return null;
                    }

                    // Use the pre-approved entity, and null out our reference.
                    final Entity next = current;
                    current = null;
                    return next;
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };

        } // End use-selector

        // Return the selected entities.
        return retVal;

    }

    /**
     * Count all <code>Entity</code>s that pass the given selection criteria.
     *
     * @param selector the <code>EntitySelector</code> that implements test that an
     *                 entity must pass to be included. This value may be
     *                 <code>null</code> (in which case the count of all entities in
     *                 the game will be returned).
     * @return the <code>int</code> count of all entities that the selector
     * accepts. This value will not be <code>null</code> but it may be
     * empty.
     */
    public int getSelectedEntityCount(final EntitySelector selector) {
        int retVal = 0;

        // If no selector was supplied, return the count of all game entities.
        if (null == selector) {
            retVal = getNoOfEntities();
        }

        // Otherwise, count the entities that meet the selection criteria.
        else {
            final Iterator<Entity> iter = this.getEntities();
            while (iter.hasNext()) {
                if (selector.accept(iter.next())) {
                    retVal++;
                }
            }

        } // End use-selector

        // Return the number of selected entities.
        return retVal;
    }

    /**
     * Get all out-of-game <code>Entity</code>s that pass the given selection
     * criteria.
     *
     * @param selector the <code>EntitySelector</code> that implements test that an
     *                 entity must pass to be included. This value may be
     *                 <code>null</code> (in which case all entities in the game will
     *                 be returned).
     * @return an <code>Enumeration</code> of all entities that the selector
     * accepts. This value will not be <code>null</code> but it may be
     * empty.
     */
    public Enumeration<Entity> getSelectedOutOfGameEntities(
            final EntitySelector selector) {
        final Enumeration<Entity> retVal;

        // If no selector was supplied, return all entities.
        if (null == selector) {
            retVal = vOutOfGame.elements();
        }

        // Otherwise, return an anonymous Enumeration
        // that selects entities in this game.
        else {
            retVal = new Enumeration<Entity>() {
                private final EntitySelector entitySelector = selector;
                private Entity current = null;
                private final Enumeration<Entity> iter = vOutOfGame.elements();

                // Do any more entities meet the selection criteria?
                public boolean hasMoreElements() {
                    // See if we have a pre-approved entity.
                    if (null == current) {

                        // Find the first acceptable entity
                        while ((null == current) && iter.hasMoreElements()) {
                            current = iter.nextElement();
                            if (!entitySelector.accept(current)) {
                                current = null;
                            }
                        }
                    }
                    return (null != current);
                }

                // Get the next entity that meets the selection criteria.
                public Entity nextElement() {
                    // Pre-approve an entity.
                    if (!hasMoreElements()) {
                        return null;
                    }

                    // Use the pre-approved entity, and null out our reference.
                    final Entity next = current;
                    current = null;
                    return next;
                }
            };

        } // End use-selector

        // Return the selected entities.
        return retVal;

    }

    /**
     * Count all out-of-game<code>Entity</code>s that pass the given selection
     * criteria.
     *
     * @param selector the <code>EntitySelector</code> that implements test that an
     *                 entity must pass to be included. This value may be
     *                 <code>null</code> (in which case the count of all out-of-game
     *                 entities will be returned).
     * @return the <code>int</code> count of all entities that the selector
     * accepts. This value will not be <code>null</code> but it may be
     * empty.
     */
    public int getSelectedOutOfGameEntityCount(final EntitySelector selector) {
        int retVal = 0;

        // If no selector was supplied, return the count of all game entities.
        if (null == selector) {
            retVal = vOutOfGame.size();
        }

        // Otherwise, count the entities that meet the selection criteria.
        else {
            final Enumeration<Entity> iter = vOutOfGame.elements();
            while (iter.hasMoreElements()) {
                if (selector.accept(iter.nextElement())) {
                    retVal++;
                }
            }

        } // End use-selector

        // Return the number of selected entities.
        return retVal;
    }

    /**
     * Returns true if the player has any valid units this turn that are not
     * infantry, not protomechs, or not either of those. This method is
     * utitilized by the "A players Infantry moves after that players other
     * units", and "A players Protomechs move after that players other units"
     * options.
     */
    public boolean checkForValidNonInfantryAndOrProtomechs(final int playerId) {
        for (final Entity entity : getPlayerEntities(getPlayer(playerId), false)) {
            boolean excluded = false;
            if ((entity instanceof Infantry)
                && getOptions().booleanOption(OptionsConstants.INIT_INF_MOVE_LATER)) {
                excluded = true;
            } else if ((entity instanceof Protomech)
                       && getOptions().booleanOption(OptionsConstants.INIT_PROTOS_MOVE_LATER)) {
                excluded = true;
            }

            if (!excluded && getTurn().isValidEntity(entity, this)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get Entities that have have a iNarc Nemesis pod attached and are situated
     * between two Coords
     *
     * @param attacker The attacking <code>Entity</code>.
     * @param target   The <code>Coords</code> of the original target.
     * @return a <code>Enumeration</code> of entities that have nemesis pods
     * attached and are located between attacker and target and are
     * friendly with the attacker.
     */
    public Enumeration<Entity> getNemesisTargets(final Entity attacker,
                                                 final Coords target) {
        final Coords attackerPos = attacker.getPosition();
        final ArrayList<Coords> in = Coords.intervening(attackerPos, target);
        final Vector<Entity> nemesisTargets = new Vector<>();
        for (final Coords c : in) {
            for (final Entity entity : getEntitiesVector(c)) {
                if (entity.isINarcedWith(INarcPod.NEMESIS)
                    && !entity.isEnemyOf(attacker)) {
                    nemesisTargets.addElement(entity);
                }
            }
        }
        return nemesisTargets.elements();
    }

    /**
     * Adds the specified game listener to receive board events from this board.
     *
     * @param listener the game listener.
     */
    public void addGameListener(final GameListener listener) {
        // Since gameListeners is transient, it could be null
        if (null == gameListeners) {
            gameListeners = new Vector<>();
        }
        gameListeners.addElement(listener);
    }

    /**
     * Removes the specified game listener.
     *
     * @param listener the game listener.
     */
    public void removeGameListener(final GameListener listener) {
        // Since gameListeners is transient, it could be null
        if (null == gameListeners) {
            gameListeners = new Vector<>();
        }
        gameListeners.removeElement(listener);
    }

    /**
     * Returns all the GameListeners.
     *
     * @return
     */
    public List<GameListener> getGameListeners() {
        // Since gameListeners is transient, it could be null
        if (null == gameListeners) {
            gameListeners = new Vector<>();
        }
        return Collections.unmodifiableList(gameListeners);
    }

    /**
     * purges all Game Listener objects.
     */
    public void purgeGameListeners() {
        // Since gameListeners is transient, it could be null
        if (null == gameListeners) {
            gameListeners = new Vector<>();
        }
        gameListeners.clear();
    }

    /**
     * Processes game events occurring on this connection by dispatching them to
     * any registered GameListener objects.
     *
     * @param event the game event.
     */
    public void processGameEvent(final GameEvent event) {
        // Since gameListeners is transient, it could be null
        if (null == gameListeners) {
            gameListeners = new Vector<>();
        }
        for (final Enumeration<GameListener> e = gameListeners.elements(); e
                .hasMoreElements(); ) {
            event.fireEvent(e.nextElement());
        }
    }

    /**
     * Returns this turn's tag information
     */
    public Vector<TagInfo> getTagInfo() {
        return tagInfoForTurn;
    }

    public void addTagInfo(final TagInfo info) {
        tagInfoForTurn.addElement(info);
    }

    public void resetTagInfo() {
        tagInfoForTurn.removeAllElements();
    }

    public void clearTagInfoShots(final Entity ae,
                                  final Coords tc) {
        for (int i = 0; i < tagInfoForTurn.size(); i++) {
            final TagInfo info = tagInfoForTurn.elementAt(i);
            final Entity attacker = getEntity(info.attackerId);
            final Targetable target = info.target;
            if (!ae.isEnemyOf(attacker) && isIn8HexRadius(target.getPosition(), tc)) {
                info.shots = info.priority;
                tagInfoForTurn.setElementAt(info, i);
            }
        }
    }

    public boolean isIn8HexRadius(final Coords c1,
                                  final Coords c2) {

        // errata says we now always use 8 hex radius
        return 8 >= c2.distance(c1);

    }

    /**
     * Get a list of flares
     */
    public Vector<Flare> getFlares() {
        return flares;
    }

    /**
     * Set the list of flares
     */
    public void setFlares(final Vector<Flare> flares) {
        this.flares = flares;
        processGameEvent(new GameBoardChangeEvent(this));
    }

    /**
     * Add a new flare
     */
    public void addFlare(final Flare flare) {
        flares.addElement(flare);
        processGameEvent(new GameBoardChangeEvent(this));
    }

    /**
     * Get a set of Coords illuminated by searchlights.
     * 
     * Note: coords could be illuminated by other sources as well, it's likely
     * that IGame.isPositionIlluminated is desired unless the searchlighted hex
     * set is being sent to the client or server.
     */
    public HashSet<Coords> getIlluminatedPositions() {
        return illuminatedPositions;
    }

    /**
     * Clear the set of searchlight illuminated hexes.
     */
    public void clearIlluminatedPositions() {
        if (null == illuminatedPositions) {
            return;
        }
        illuminatedPositions.clear();
    }

    /**
     * Setter for the list of Coords illuminated by search lights.
     */
    public void setIlluminatedPositions(final HashSet<Coords> ip) {
        if (null == ip) {
            new RuntimeException("Illuminated Positions is null.").printStackTrace();
        }
        illuminatedPositions = ip;
        processGameEvent(new GameBoardChangeEvent(this));
    }

    /**
     * Add a new hex to the collection of Coords illuminated by searchlights.
     *
     * @return True if a new hex was added, else false if the set already
     * contained the input hex.
     */
    public boolean addIlluminatedPosition(final Coords c) {
        final boolean rv = illuminatedPositions.add(c);
        processGameEvent(new GameBoardChangeEvent(this));
        return rv;
    }

    /**
     * Returns the level of illumination for a given coords.  Different light
     * sources affect how much the night-time penalties are reduced. Note: this
     * method should be used for determining is a Coords/Hex is illuminated, not
     * IGame. getIlluminatedPositions(), as that just returns the hexes that
     * are effected by spotlights, whereas this one considers searchlights as
     * well as other light sources.
     */
    public int isPositionIlluminated(final Coords c) {
        // fix for NPE when recovering spacecraft while in visual range of enemy
        if (getBoard().inSpace()) {
            return ILLUMINATED_NONE;
        }
        // Flares happen first, because they totally negate nighttime penalties
        for (final Flare flare : flares) {
            if (flare.illuminates(c)) {
                return ILLUMINATED_FLARE;
            }
        }
        IHex hex = getBoard().getHex(c);

        // Searchlights reduce nighttime penalties by up to 3 points.
        if (illuminatedPositions.contains(c)) {
            return ILLUMINATED_LIGHT;
        }

        // Fires can reduce nighttime penalties by up to 2 points.
        if (null != hex && hex.containsTerrain(Terrains.FIRE)) {
            return ILLUMINATED_FIRE;
        }
        // If we are adjacent to a burning hex, we are also illuminated
        for (int dir = 0; 6 > dir; dir++) {
            final Coords adj = c.translated(dir);
            hex = getBoard().getHex(adj);
            if (null != hex && hex.containsTerrain(Terrains.FIRE)) {
                return ILLUMINATED_FIRE;
            }
        }
        return ILLUMINATED_NONE;
    }

    /**
     * Age the flare list and remove any which have burnt out Artillery flares
     * drift with wind. (called at end of turn)
     */
    public Vector<Report> ageFlares() {
        final Vector<Report> reports = new Vector<>();
        Report r;
        for (int i = flares.size() - 1; 0 <= i; i--) {
            final Flare flare = flares.elementAt(i);
            r = new Report(5235);
            r.add(flare.position.getBoardNum());
            r.newlines = 0;
            reports.addElement(r);
            if (0 != (flare.flags & Flare.F_IGNITED)) {
                flare.turnsToBurn--;
                if (0 != (flare.flags & Flare.F_DRIFTING)) {
                    final int dir = planetaryConditions.getWindDirection();
                    final int str = planetaryConditions.getWindStrength();

                    // strength 1 and 2: drift 1 hex
                    // strength 3: drift 2 hexes
                    // strength 4: drift 3 hexes
                    // for each above strenght 4 (storm), drift one more
                    if (0 < str) {
                        flare.position = flare.position.translated(dir);
                        if (2 < str) {
                            flare.position = flare.position.translated(dir);
                        }
                        if (3 < str) {
                            flare.position = flare.position.translated(dir);
                        }
                        if (4 < str) {
                            flare.position = flare.position.translated(dir);
                        }
                        if (5 < str) {
                            flare.position = flare.position.translated(dir);
                        }
                        r = new Report(5236);
                        r.add(flare.position.getBoardNum());
                        r.newlines = 0;
                        reports.addElement(r);
                    }
                }
            } else {
                r = new Report(5237);
                r.newlines = 0;
                reports.addElement(r);
                flare.flags |= Flare.F_IGNITED;
            }
            if (0 >= flare.turnsToBurn) {
                r = new Report(5238);
                reports.addElement(r);
                flares.removeElementAt(i);
            } else {
                r = new Report(5239);
                r.add(flare.turnsToBurn);
                reports.addElement(r);
                flares.setElementAt(flare, i);
            }
        }
        processGameEvent(new GameBoardChangeEvent(this));
        return reports;
    }

    public boolean gameTimerIsExpired() {
        return ((getOptions().booleanOption(OptionsConstants.VICTORY_USE_GAME_TURN_LIMIT)) && (getRoundCount() == getOptions()
                .intOption(OptionsConstants.VICTORY_GAME_TURN_LIMIT)));
    }

    public void createVictoryConditions() {
        victory = new Victory(getOptions());
    }

    public Victory getVictory() {
        return victory;
    }

    // a shortcut function for determining whether vectored movement is
    // applicable
    public boolean useVectorMove() {
        return getOptions().booleanOption(OptionsConstants.ADVAERORULES_ADVANCED_MOVEMENT)
               && board.inSpace();
    }

    /**
     * Adds a pending Control roll to the list for this phase.
     */
    public void addControlRoll(final PilotingRollData control) {
        controlRolls.addElement(control);
    }

    /**
     * Returns an Enumeration of pending Control rolls.
     */
    public Enumeration<PilotingRollData> getControlRolls() {
        return controlRolls.elements();
    }

    /**
     * Resets the Control Roll list for a given entity.
     */
    public void resetControlRolls(final Entity entity) {
        PilotingRollData roll;
        final Vector<Integer> rollsToRemove = new Vector<>();
        int i;

        // first, find all the rolls belonging to the target entity
        for (i = 0; i < controlRolls.size(); i++) {
            roll = controlRolls.elementAt(i);
            if (roll.getEntityId() == entity.getId()) {
                rollsToRemove.addElement(i);
            }
        }

        // now, clear them out
        for (i = rollsToRemove.size() - 1; -1 < i; i--) {
            controlRolls.removeElementAt(rollsToRemove.elementAt(i));
        }
    }

    /**
     * Resets the PSR list.
     */
    public void resetControlRolls() {
        controlRolls.removeAllElements();
    }

    /**
     * A set of checks for aero units to make sure that the movement order is
     * maintained
     */
    public boolean checkForValidSpaceStations(final int playerId) {
        for (final Entity entity : getPlayerEntities(getPlayer(playerId), false)) {
            if ((entity instanceof SpaceStation)
                && getTurn().isValidEntity(entity, this)) {
                return true;
            }
        }
        return false;
    }

    public boolean checkForValidJumpships(final int playerId) {
        for (final Entity entity : getPlayerEntities(getPlayer(playerId), false)) {
            if ((entity instanceof Jumpship) && !(entity instanceof Warship)
                && getTurn().isValidEntity(entity, this)) {
                return true;
            }
        }
        return false;
    }

    public boolean checkForValidWarships(final int playerId) {
        for (final Entity entity : getPlayerEntities(getPlayer(playerId), false)) {
            if ((entity instanceof Warship)
                && getTurn().isValidEntity(entity, this)) {
                return true;
            }
        }
        return false;
    }

    public boolean checkForValidDropships(final int playerId) {
        for (final Entity entity : getPlayerEntities(getPlayer(playerId), false)) {
            if ((entity instanceof Dropship)
                && getTurn().isValidEntity(entity, this)) {
                return true;
            }
        }
        return false;
    }

    public boolean checkForValidSmallCraft(final int playerId) {
        for (final Entity entity : getPlayerEntities(getPlayer(playerId), false)) {
            if ((entity instanceof SmallCraft)
                && getTurn().isValidEntity(entity, this)) {
                return true;
            }
        }
        return false;
    }

    public PlanetaryConditions getPlanetaryConditions() {
        return planetaryConditions;
    }

    public void setPlanetaryConditions(final PlanetaryConditions conditions) {
        if (null == conditions) {
            LOGGER.log(getClass(), "setPlanetaryConditions(PlanetaryConditions)",
                       new NullPointerException("Can't set the planetary conditions to null!"));
        } else {
            planetaryConditions.alterConditions(conditions);
            processGameEvent(new GameSettingsChangeEvent(this));
        }
    }

    public void addSmokeCloud(final SmokeCloud cloud) {
        smokeCloudList.add(cloud);
    }

    public List<SmokeCloud> getSmokeCloudList() {
        return smokeCloudList;
    }

    public void removeSmokeClouds(final List<SmokeCloud> cloudsToRemove) {
        for (final SmokeCloud cloud : cloudsToRemove) {
            smokeCloudList.remove(cloud);
        }
    }

    /**
     * Updates the map that maps a position to the list of Entity's in that
     * position.
     *
     * @param e
     */
    public synchronized void updateEntityPositionLookup(final Entity e,
                                                        final HashSet<Coords> oldPositions) {
        final HashSet<Coords> newPositions = e.getOccupiedCoords();
        // Check to see that the position has actually changed
        if (newPositions.equals(oldPositions)) {
            return;
        }

        // Remove the old cached location(s)
        if (null != oldPositions) {
            for (final Coords pos : oldPositions) {
                final HashSet<Integer> posEntities = entityPosLookup.get(pos);
                if (null != posEntities) {
                    posEntities.remove(e.getId());
                }
            }
        }

        // Add Entity for each position
        for (final Coords pos : newPositions) {
            HashSet<Integer> posEntities = entityPosLookup.get(pos);
            if (null == posEntities) {
                posEntities = new HashSet<>();
                posEntities.add(e.getId());
                entityPosLookup.put(pos, posEntities);
            } else {
                posEntities.add(e.getId());
            }
        }
    }

    private void removeEntityPositionLookup(final Entity e) {
        // Remove Entity from cache
        for (final Coords pos : e.getOccupiedCoords()) {
            final HashSet<Integer> posEntities = entityPosLookup.get(pos);
            if (null != posEntities) {
                posEntities.remove(e.getId());
            }
        }
    }

    private void resetEntityPositionLookup() {
        entityPosLookup.clear();
        for (final Entity e : entities) {
            updateEntityPositionLookup(e, null);
        }
    }

    private int countEntitiesInCache(final List<Integer> entitiesInCache) {
        int count = 0;
        for (final Coords c : entityPosLookup.keySet()) {
            count += entityPosLookup.get(c).size();
            entitiesInCache.addAll(entityPosLookup.get(c));
        }
        return count;
    }
    
    /**
     * A check to ensure that the position cache is properly updated.  This 
     * is only used for debugging purposes, and will cause a number of things
     * to slow down.
     */
    @SuppressWarnings("unused")
    private void checkPositionCacheConsistency() {
        final String methodName = "checkPositionCacheConsistency()";
        
        // Sanity check on the position cache
        //  This could be removed once we are confident the cache is working
        final List<Integer> entitiesInCache = new ArrayList<>();
        final List<Integer> entitiesInVector = new ArrayList<>();
        final int entitiesInCacheCount = countEntitiesInCache(entitiesInCache);
        int entityVectorSize = 0;
        for (final Entity e : entities) {
            if (null != e.getPosition()) {
                entityVectorSize++;
                entitiesInVector.add(e.getId());
            }
        }
        Collections.sort(entitiesInCache);
        Collections.sort(entitiesInVector);
        if ((entitiesInCacheCount != entityVectorSize)
            && (Phase.PHASE_DEPLOYMENT != getPhase())
            && (Phase.PHASE_EXCHANGE != getPhase())
            && (Phase.PHASE_LOUNGE != getPhase())
            && (Phase.PHASE_INITIATIVE_REPORT != getPhase())
            && (Phase.PHASE_INITIATIVE != getPhase())) {
            LOGGER.log(getClass(), methodName,
                       new RuntimeException("Entities vector has " + entities.size() + " but pos lookup cache has " +
                                            entitiesInCache.size() + " entities!"));
            final List<Integer> missingIds = new ArrayList<>();
            for (final Integer id : entitiesInVector) {
                if (!entitiesInCache.contains(id)) {
                    missingIds.add(id);
                }
            }
            LOGGER.log(getClass(), methodName, LogLevel.WARNING, "Missing ids: " + missingIds);
        }
        for (final Entity e : entities) {
            final HashSet<Coords> positions = e.getOccupiedCoords();
            for (final Coords c : positions) {
                final HashSet<Integer> ents = entityPosLookup.get(c);
                if ((null != ents) && !ents.contains(e.getId())) {
                    LOGGER.log(getClass(), methodName,
                               new RuntimeException("Entity " + e.getId() + " is in " + e.getPosition() +
                                                    " however the position cache does not have it in that position!"));
                }
            }
        }
        for (final Coords c : entityPosLookup.keySet()) {
            for (final Integer eId : entityPosLookup.get(c)) {
                final Entity e = getEntity(eId);
                if (null == e) {
                    continue;
                }
                final HashSet<Coords> positions = e.getOccupiedCoords();
                if (!positions.contains(c)) {
                    LOGGER.log(getClass(), methodName,
                               new RuntimeException("Entity Position Cache thinks Entity " + eId + "is in " + c +
                                                    " but the Entity thinks it's in " + e.getPosition()));
                }
            }
        }
    }

}
