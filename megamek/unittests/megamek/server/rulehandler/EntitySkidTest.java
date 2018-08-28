package megamek.server.rulehandler;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.Collections;

import org.junit.Test;

import megamek.common.Coords;
import megamek.common.Entity;
import megamek.common.EntityMovementType;
import megamek.common.Game;
import megamek.common.IBoard;
import megamek.common.IGame;
import megamek.common.IPlayer;
import megamek.common.MoveStep;
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

}
