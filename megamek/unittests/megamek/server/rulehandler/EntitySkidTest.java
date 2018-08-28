package megamek.server.rulehandler;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.Collections;

import org.junit.Test;

import megamek.common.Building;
import megamek.common.Coords;
import megamek.common.Entity;
import megamek.common.EntityMovementMode;
import megamek.common.EntityMovementType;
import megamek.common.Game;
import megamek.common.Hex;
import megamek.common.IBoard;
import megamek.common.IGame;
import megamek.common.IPlayer;
import megamek.common.MoveStep;
import megamek.common.Tank;
import megamek.common.Terrain;
import megamek.common.Terrains;
import megamek.common.net.Packet;
import megamek.common.options.GameOptions;
import megamek.server.Server;

public class EntitySkidTest {
    
    private Entity createMockEntity(final int id) {
        Entity entity = mock(Entity.class);
        when(entity.getId()).thenReturn(id);
        IPlayer owner = mock(IPlayer.class);
        when(owner.getColorIndex()).thenReturn(1);
        when(entity.getOwner()).thenReturn(owner);
        return entity;
    }
    
    @Test
    public void skidOffMapRemovesEntityAndCarriedWithPushOffBoardOption() {
        Entity entity = createMockEntity(1);
        IGame game = new Game();
        game.addEntity(entity, false);
        Entity loaded = createMockEntity(2);
        when(entity.getLoadedUnits()).thenReturn(Collections.singletonList(loaded));
        game.addEntity(loaded, false);
        Entity swarmer = createMockEntity(3);
        when(entity.getSwarmAttackerId()).thenReturn(3);
        game.addEntity(swarmer, false);
        IBoard board = mock(IBoard.class);
        when(board.contains(any(Coords.class))).thenReturn(false);
        GameOptions options = mock(GameOptions.class);
        when(options.booleanOption(anyString())).thenReturn(true);
        game.setOptions(options);
        game.setBoard(board);
        
        EntitySkid skid = new EntitySkid(entity, new Coords(0, 0), 0, 0, 5, mock(MoveStep.class),
                EntityMovementType.MOVE_WALK, false, mock(Server.class));
        skid.checkSkidOffMap(game);
        
        assertFalse(game.getEntitiesVector().contains(entity));
        assertFalse(game.getEntitiesVector().contains(loaded));
        assertFalse(game.getEntitiesVector().contains(swarmer));
        assertEquals(skid.getPackets().stream()
                .filter(p -> p.getCommand() == Packet.COMMAND_ENTITY_REMOVE).count(), 3);
    }

    @Test
    public void skidOffMapStopsSkidWithoutPushOffBoardOption() {
        Entity entity = createMockEntity(1);
        IGame game = new Game();
        game.addEntity(entity, false);
        Entity loaded = createMockEntity(2);
        when(entity.getLoadedUnits()).thenReturn(Collections.singletonList(loaded));
        game.addEntity(loaded, false);
        Entity swarmer = createMockEntity(3);
        when(entity.getSwarmAttackerId()).thenReturn(3);
        game.addEntity(swarmer, false);
        IBoard board = mock(IBoard.class);
        when(board.contains(any(Coords.class))).thenReturn(false);
        GameOptions options = mock(GameOptions.class);
        when(options.booleanOption(anyString())).thenReturn(false);
        game.setOptions(options);
        game.setBoard(board);
        
        EntitySkid skid = new EntitySkid(entity, new Coords(0, 0), 0, 0, 5, mock(MoveStep.class),
                EntityMovementType.MOVE_WALK, false, mock(Server.class));
        skid.checkSkidOffMap(game);
        
        assertTrue(game.getEntitiesVector().contains(entity));
        assertTrue(game.getEntitiesVector().contains(loaded));
        assertTrue(game.getEntitiesVector().contains(swarmer));
    }

    @Test
    public void entityDropsToLowerHex() {
        Entity entity = mock(Entity.class);
        when(entity.getMovementMode()).thenReturn(EntityMovementMode.BIPED);
        IGame game = new Game();
        game.addEntity(entity, false);
        Hex curHex = new Hex();
        curHex.setLevel(2);
        Hex lowerHex = new Hex();
        lowerHex.setLevel(1);
        IBoard board = mock(IBoard.class);
        when(board.getHex(any(Coords.class))).thenReturn(curHex);
        game.setBoard(board);
        EntitySkid skid = new EntitySkid(entity, new Coords(0, 0), 1, 0, 5, mock(MoveStep.class),
                EntityMovementType.MOVE_WALK, false, mock(Server.class));
        skid.initStartingValues(game);

        skid.calcNextElevation(lowerHex);

        assertEquals(lowerHex.floor(), skid.getNextAltitude());
    }

    @Test
    public void vtolMaintainsElevation() {
        Entity entity = mock(Entity.class);
        when(entity.getMovementMode()).thenReturn(EntityMovementMode.VTOL);
        IGame game = new Game();
        game.addEntity(entity, false);
        Hex curHex = new Hex();
        curHex.setLevel(2);
        Hex lowerHex = new Hex();
        lowerHex.setLevel(1);
        IBoard board = mock(IBoard.class);
        when(board.getHex(any(Coords.class))).thenReturn(curHex);
        game.setBoard(board);
        EntitySkid skid = new EntitySkid(entity, new Coords(0, 0), 5, 0, 5, mock(MoveStep.class),
                EntityMovementType.MOVE_WALK, false, mock(Server.class));
        skid.initStartingValues(game);

        skid.calcNextElevation(lowerHex);

        assertEquals(skid.getCurrentElevation() + curHex.floor(), skid.getNextAltitude());
    }
    
    @Test
    public void wigeRemainsAboveSurface() {
        Entity entity = mock(Entity.class);
        when(entity.getMovementMode()).thenReturn(EntityMovementMode.WIGE);
        IGame game = new Game();
        game.addEntity(entity, false);
        Hex curHex = new Hex();
        curHex.setLevel(2);
        Hex lowerHex = new Hex();
        lowerHex.setLevel(1);
        IBoard board = mock(IBoard.class);
        when(board.getHex(any(Coords.class))).thenReturn(curHex);
        game.setBoard(board);
        EntitySkid skid = new EntitySkid(entity, new Coords(0, 0), 1, 0, 5, mock(MoveStep.class),
                EntityMovementType.MOVE_WALK, false, mock(Server.class));
        skid.initStartingValues(game);

        skid.calcNextElevation(lowerHex);

        assertEquals(lowerHex.floor() + 1, skid.getNextAltitude());
    }
    
    @Test
    public void groundedWIGEDropsToSurface() {
        Entity entity = mock(Entity.class);
        when(entity.getMovementMode()).thenReturn(EntityMovementMode.WIGE);
        IGame game = new Game();
        game.addEntity(entity, false);
        Hex curHex = new Hex();
        curHex.setLevel(2);
        Hex lowerHex = new Hex();
        lowerHex.setLevel(1);
        IBoard board = mock(IBoard.class);
        when(board.getHex(any(Coords.class))).thenReturn(curHex);
        game.setBoard(board);
        EntitySkid skid = new EntitySkid(entity, new Coords(0, 0), 0, 0, 5, mock(MoveStep.class),
                EntityMovementType.MOVE_WALK, false, mock(Server.class));
        skid.initStartingValues(game);

        skid.calcNextElevation(lowerHex);

        assertEquals(lowerHex.floor(), skid.getNextAltitude());
    }

    @Test
    public void entityDropsToRoofOfBuilding() {
        Entity entity = mock(Entity.class);
        when(entity.getMovementMode()).thenReturn(EntityMovementMode.BIPED);
        IGame game = new Game();
        game.addEntity(entity, false);
        Hex curHex = new Hex();
        curHex.setLevel(5);
        Hex lowerHex = new Hex();
        lowerHex.setLevel(1);
        lowerHex.addTerrain(new Terrain(Terrains.BLDG_ELEV, 1));
        IBoard board = mock(IBoard.class);
        when(board.getHex(any(Coords.class))).thenReturn(curHex);
        game.setBoard(board);
        EntitySkid skid = new EntitySkid(entity, new Coords(0, 0), 1, 0, 5, mock(MoveStep.class),
                EntityMovementType.MOVE_WALK, false, mock(Server.class));
        skid.initStartingValues(game);

        skid.calcNextElevation(lowerHex);

        assertEquals(lowerHex.ceiling(), skid.getNextAltitude());
    }

    @Test
    public void entityDropsToBridgeWithCorrectExitDirection() {
        Entity entity = mock(Entity.class);
        when(entity.getMovementMode()).thenReturn(EntityMovementMode.BIPED);
        IGame game = new Game();
        game.addEntity(entity, false);
        Hex curHex = new Hex();
        curHex.setLevel(5);
        Hex lowerHex = new Hex();
        lowerHex.setLevel(1);
        lowerHex.addTerrain(new Terrain(Terrains.BRIDGE, 1, true, (1 << 3)));
        IBoard board = mock(IBoard.class);
        when(board.getHex(any(Coords.class))).thenReturn(curHex);
        game.setBoard(board);
        EntitySkid skid = new EntitySkid(entity, new Coords(0, 0), 1, 0, 5, mock(MoveStep.class),
                EntityMovementType.MOVE_WALK, false, mock(Server.class));
        skid.initStartingValues(game);

        skid.calcNextElevation(lowerHex);

        assertEquals(lowerHex.ceiling(), skid.getNextAltitude());
    }

    @Test
    public void entityMissesBridgeWithOtherExitDirection() {
        Entity entity = mock(Entity.class);
        when(entity.getMovementMode()).thenReturn(EntityMovementMode.BIPED);
        IGame game = new Game();
        game.addEntity(entity, false);
        Hex curHex = new Hex();
        curHex.setLevel(5);
        Hex lowerHex = new Hex();
        lowerHex.setLevel(1);
        lowerHex.addTerrain(new Terrain(Terrains.BRIDGE, 1, true, 1));
        IBoard board = mock(IBoard.class);
        when(board.getHex(any(Coords.class))).thenReturn(curHex);
        game.setBoard(board);
        EntitySkid skid = new EntitySkid(entity, new Coords(0, 0), 1, 0, 5, mock(MoveStep.class),
                EntityMovementType.MOVE_WALK, false, mock(Server.class));
        skid.initStartingValues(game);

        skid.calcNextElevation(lowerHex);

        assertEquals(lowerHex.floor(), skid.getNextAltitude());
    }

    @Test
    public void entityMissesHigherBridge() {
        Entity entity = mock(Entity.class);
        when(entity.getMovementMode()).thenReturn(EntityMovementMode.BIPED);
        IGame game = new Game();
        game.addEntity(entity, false);
        Hex curHex = new Hex();
        curHex.setLevel(3);
        Hex lowerHex = new Hex();
        lowerHex.setLevel(1);
        lowerHex.addTerrain(new Terrain(Terrains.BRIDGE, 3, true, (1 << 3)));
        IBoard board = mock(IBoard.class);
        when(board.getHex(any(Coords.class))).thenReturn(curHex);
        game.setBoard(board);
        EntitySkid skid = new EntitySkid(entity, new Coords(0, 0), 1, 0, 5, mock(MoveStep.class),
                EntityMovementType.MOVE_WALK, false, mock(Server.class));
        skid.initStartingValues(game);

        skid.calcNextElevation(lowerHex);

        assertEquals(lowerHex.floor(), skid.getNextAltitude());
    }

    @Test
    public void hoverTankSkidsAcrossWater() {
        Tank tank = mock(Tank.class);
        when(tank.getMovementMode()).thenReturn(EntityMovementMode.HOVER);
        IGame game = new Game();
        game.addEntity(tank, false);
        Hex curHex = new Hex();
        curHex.setLevel(2);
        Hex lowerHex = new Hex();
        lowerHex.setLevel(1);
        lowerHex.addTerrain(new Terrain(Terrains.WATER, 3));
        IBoard board = mock(IBoard.class);
        when(board.getHex(any(Coords.class))).thenReturn(curHex);
        game.setBoard(board);
        EntitySkid skid = new EntitySkid(tank, new Coords(0, 0), 1, 0, 5, mock(MoveStep.class),
                EntityMovementType.MOVE_WALK, false, mock(Server.class));
        skid.initStartingValues(game);

        skid.calcNextElevation(lowerHex);

        assertEquals(lowerHex.surface(), skid.getNextAltitude());
        assertTrue(lowerHex.floor() < skid.getNextAltitude());
    }

    @Test
    public void nonHoverTankSkidsIntoWater() {
        Entity entity = mock(Entity.class);
        when(entity.getMovementMode()).thenReturn(EntityMovementMode.BIPED);
        IGame game = new Game();
        game.addEntity(entity, false);
        Hex curHex = new Hex();
        curHex.setLevel(2);
        Hex lowerHex = new Hex();
        lowerHex.setLevel(1);
        lowerHex.addTerrain(new Terrain(Terrains.WATER, 3));
        IBoard board = mock(IBoard.class);
        when(board.getHex(any(Coords.class))).thenReturn(curHex);
        game.setBoard(board);
        EntitySkid skid = new EntitySkid(entity, new Coords(0, 0), 1, 0, 5, mock(MoveStep.class),
                EntityMovementType.MOVE_WALK, false, mock(Server.class));
        skid.initStartingValues(game);

        skid.calcNextElevation(lowerHex);

        assertEquals(lowerHex.floor(), skid.getNextAltitude());
        assertTrue(lowerHex.surface() > skid.getNextAltitude());
    }

    @Test
    public void entitySkidsAcrossIce() {
        Entity entity = mock(Entity.class);
        when(entity.getMovementMode()).thenReturn(EntityMovementMode.BIPED);
        IGame game = new Game();
        game.addEntity(entity, false);
        Hex curHex = new Hex();
        curHex.setLevel(2);
        Hex lowerHex = new Hex();
        lowerHex.setLevel(1);
        lowerHex.addTerrain(new Terrain(Terrains.ICE, 3));
        IBoard board = mock(IBoard.class);
        when(board.getHex(any(Coords.class))).thenReturn(curHex);
        game.setBoard(board);
        EntitySkid skid = new EntitySkid(entity, new Coords(0, 0), 1, 0, 5, mock(MoveStep.class),
                EntityMovementType.MOVE_WALK, false, mock(Server.class));
        skid.initStartingValues(game);

        skid.calcNextElevation(lowerHex);

        assertEquals(lowerHex.surface(), skid.getNextAltitude());
    }

    @Test
    public void entityCrashesIntoHigherHex() {
        Entity entity = mock(Entity.class);
        when(entity.getMovementMode()).thenReturn(EntityMovementMode.BIPED);
        IGame game = new Game();
        game.addEntity(entity, false);
        Hex curHex = new Hex();
        curHex.setLevel(2);
        Hex higherHex = new Hex();
        higherHex.setLevel(3);
        IBoard board = mock(IBoard.class);
        when(board.getHex(any(Coords.class))).thenReturn(curHex);
        game.setBoard(board);
        EntitySkid skid = new EntitySkid(entity, new Coords(0, 0), 0, 0, 5, mock(MoveStep.class),
                EntityMovementType.MOVE_WALK, false, mock(Server.class));
        skid.initStartingValues(game);
        
        skid.calcNextElevation(higherHex);
        
        assertTrue(skid.checkForCrashIntoTerrain(game, higherHex));
    }

    @Test
    public void vtolCrashesIntoWoodsHex() {
        final int STARTING_ELEV = 1;
        Entity entity = mock(Entity.class);
        when(entity.getMovementMode()).thenReturn(EntityMovementMode.VTOL);
        IGame game = new Game();
        game.addEntity(entity, false);
        Hex curHex = new Hex();
        curHex.setLevel(0);
        Hex woodedHex = new Hex();
        woodedHex.setLevel(0);
        woodedHex.addTerrain(new Terrain(Terrains.WOODS, 1)); // light woods
        IBoard board = mock(IBoard.class);
        when(board.getHex(any(Coords.class))).thenReturn(curHex);
        game.setBoard(board);
        EntitySkid skid = new EntitySkid(entity, new Coords(0, 0), STARTING_ELEV, 0, 5, mock(MoveStep.class),
                EntityMovementType.MOVE_WALK, false, mock(Server.class));
        skid.initStartingValues(game);
        
        skid.calcNextElevation(woodedHex);
        
        assertTrue(skid.checkForCrashIntoTerrain(game, woodedHex));
    }

    @Test
    public void vtolSkidsOverWoodsHex() {
        final int STARTING_ELEV = 2;
        Entity entity = mock(Entity.class);
        when(entity.getMovementMode()).thenReturn(EntityMovementMode.VTOL);
        IGame game = new Game();
        game.addEntity(entity, false);
        Hex curHex = new Hex();
        curHex.setLevel(0);
        Hex woodedHex = new Hex();
        woodedHex.setLevel(0);
        woodedHex.addTerrain(new Terrain(Terrains.WOODS, 1)); // light woods
        IBoard board = mock(IBoard.class);
        when(board.getHex(any(Coords.class))).thenReturn(curHex);
        game.setBoard(board);
        EntitySkid skid = new EntitySkid(entity, new Coords(0, 0), STARTING_ELEV, 0, 5, mock(MoveStep.class),
                EntityMovementType.MOVE_WALK, false, mock(Server.class));
        skid.initStartingValues(game);
        
        skid.calcNextElevation(woodedHex);
        
        assertFalse(skid.checkForCrashIntoTerrain(game, woodedHex));
    }

    @Test
    public void entityCrashesIntoWallFromAbove() {
        Entity entity = mock(Entity.class);
        when(entity.getMovementMode()).thenReturn(EntityMovementMode.BIPED);
        IGame game = new Game();
        game.addEntity(entity, false);
        Hex curHex = new Hex();
        curHex.setLevel(5);
        Hex lowerHex = new Hex();
        lowerHex.setLevel(1);
        lowerHex.addTerrain(new Terrain(Terrains.BLDG_ELEV, 1));
        Building building = mock(Building.class);
        when(building.getType()).thenReturn(Building.WALL);
        IBoard board = mock(IBoard.class);
        when(board.getHex(any(Coords.class))).thenReturn(curHex);
        when(board.getBuildingAt(any(Coords.class))).thenReturn(building);
        game.setBoard(board);
        EntitySkid skid = new EntitySkid(entity, new Coords(0, 0), 1, 0, 5, mock(MoveStep.class),
                EntityMovementType.MOVE_WALK, false, mock(Server.class));
        skid.initStartingValues(game);

        skid.calcNextElevation(lowerHex);

        assertTrue(skid.checkForCrashIntoTerrain(game, lowerHex));
    }

    @Test
    public void wigeRisesOneLevelOverTerrain() {
        Entity entity = mock(Entity.class);
        when(entity.getMovementMode()).thenReturn(EntityMovementMode.WIGE);
        IGame game = new Game();
        game.addEntity(entity, false);
        Hex curHex = new Hex();
        curHex.setLevel(2);
        Hex higherHex = new Hex();
        higherHex.setLevel(3);
        IBoard board = mock(IBoard.class);
        when(board.getHex(any(Coords.class))).thenReturn(curHex);
        game.setBoard(board);
        EntitySkid skid = new EntitySkid(entity, new Coords(0, 0), 1, 0, 5, mock(MoveStep.class),
                EntityMovementType.MOVE_WALK, false, mock(Server.class));
        skid.initStartingValues(game);
        
        skid.calcNextElevation(higherHex);
        
        assertFalse(skid.checkForCrashIntoTerrain(game, higherHex));
        assertEquals(skid.getNextElevation(), 1);
    }

    @Test
    public void wigeCrashesTwoLevelsOverTerrain() {
        Entity entity = mock(Entity.class);
        when(entity.getMovementMode()).thenReturn(EntityMovementMode.WIGE);
        IGame game = new Game();
        game.addEntity(entity, false);
        Hex curHex = new Hex();
        curHex.setLevel(2);
        Hex higherHex = new Hex();
        higherHex.setLevel(4);
        IBoard board = mock(IBoard.class);
        when(board.getHex(any(Coords.class))).thenReturn(curHex);
        game.setBoard(board);
        EntitySkid skid = new EntitySkid(entity, new Coords(0, 0), 1, 0, 5, mock(MoveStep.class),
                EntityMovementType.MOVE_WALK, false, mock(Server.class));
        skid.initStartingValues(game);
        
        skid.calcNextElevation(higherHex);
        
        assertTrue(skid.checkForCrashIntoTerrain(game, higherHex));
    }

    @Test
    public void airmechSkidsOverTerrainTwoLevelsHigher() {
        Entity entity = mock(Entity.class);
        when(entity.hasETypeFlag(anyLong()))
            .thenAnswer(inv -> ((Long) inv.getArguments()[0]).longValue() == Entity.ETYPE_LAND_AIR_MECH);
        when(entity.getMovementMode()).thenReturn(EntityMovementMode.WIGE);
        IGame game = new Game();
        game.addEntity(entity, false);
        Hex curHex = new Hex();
        curHex.setLevel(2);
        Hex higherHex = new Hex();
        higherHex.setLevel(4);
        IBoard board = mock(IBoard.class);
        when(board.getHex(any(Coords.class))).thenReturn(curHex);
        game.setBoard(board);
        EntitySkid skid = new EntitySkid(entity, new Coords(0, 0), 1, 0, 5, mock(MoveStep.class),
                EntityMovementType.MOVE_WALK, false, mock(Server.class));
        skid.initStartingValues(game);
        
        skid.calcNextElevation(higherHex);
        
        assertFalse(skid.checkForCrashIntoTerrain(game, higherHex));
        assertEquals(skid.getNextElevation(), 0);
    }

}
