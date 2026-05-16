package de.unisaarland.cs.se.selab.clouds

import de.unisaarland.cs.se.selab.Constants
import de.unisaarland.cs.se.selab.board.BoardData
import de.unisaarland.cs.se.selab.board.BoardHandler
import de.unisaarland.cs.se.selab.board.Coordinate
import de.unisaarland.cs.se.selab.board.Direction
import de.unisaarland.cs.se.selab.board.Field
import de.unisaarland.cs.se.selab.board.Plantation
import de.unisaarland.cs.se.selab.board.Tile
import de.unisaarland.cs.se.selab.board.TileType
import de.unisaarland.cs.se.selab.plants.Plant
import de.unisaarland.cs.se.selab.plants.PlantType
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class CloudTest {
    val tile0 = Plantation(
        0,
        Coordinate(0, 0),
        Direction.SOUTH,
        100,
        TileType.PLANTATION,
        3000,
        Plant(PlantType.APPLE, Constants.apple, 1)
    )
    val tile1 = Plantation(
        1,
        Coordinate(0, 2),
        Direction.NORTH,
        100,
        TileType.PLANTATION,
        3000,
        Plant(PlantType.APPLE, Constants.apple, 1)
    )
    val tile2 = Field(
        2,
        Coordinate(-2, 2),
        Direction.NORTHEAST,
        100,
        TileType.FIELD,
        2500,
        Plant(PlantType.POTATO, Constants.potato, 1),
        setOf(PlantType.POTATO)
    )
    val tile4 = Tile(
        4,
        Coordinate(-1, 1),
        Direction.NORTHWEST,
        false,
        null,
        TileType.ROAD
    )
    val tile17 = Plantation(
        17,
        Coordinate(1, 1),
        null,
        100,
        TileType.VILLAGE,
        3000,
        Plant(PlantType.APPLE, Constants.apple, 1)
    )
    val tile18 = Plantation(
        18,
        Coordinate(2, 0),
        Direction.SOUTHWEST,
        100,
        TileType.PLANTATION,
        3000,
        Plant(PlantType.POTATO, Constants.potato, 1),
    )
    val board =
        BoardData(
            mapOf(
                Pair(0, tile0),
                Pair(1, tile1),
                Pair(2, tile2),
                Pair(4, tile4),
                Pair(17, tile17),
                Pair(18, tile18)
            )
        )

    @Test
    fun cloudMovementTest() {
        val cloud1 = Cloud(33, -1, 0, 20)
        val cloud2 = Cloud(37, 2, 4, 20)
        val cloud3 = Cloud(308, 222, 18, 20)
        val data = CloudData(309, mutableListOf(cloud1, cloud2, cloud3))
        val handler = CloudHandler(data, board)
        handler.moveClouds()
        assertTrue { cloud1.location == 0 }
        assertTrue { cloud2.location == 4 }
        assertTrue { data.clouds.size == 2 }
    }

    @Test
    fun cloudRainTest() {
        val cloud1 = Cloud(33, -1, 0, 20000)
        val cloud2 = Cloud(37, 2, 4, 20000)
        val data = CloudData(38, mutableListOf(cloud1, cloud2))
        val handler = CloudHandler(data, board)
        val boardHandler = BoardHandler()
        boardHandler.reduceSoil(12, board)
        handler.moveClouds()
        assertTrue { data.clouds.size == 1 }
        assertTrue { cloud1.waterAmount == 19800 }
    }

    @Test
    fun cloudMergeTest() {
        val cloud1 = Cloud(33, -1, 0, 20000)
        val cloud2 = Cloud(37, 2, 1, 20000)
        val data = CloudData(38, mutableListOf(cloud1, cloud2))
        val handler = CloudHandler(data, board)
        handler.moveClouds()
        assertTrue { data.clouds.size == 1 }
        assertTrue { data.clouds[0].waterAmount == 40000 }
        assertTrue { data.clouds[0].id == 38 }
        assertTrue { data.clouds[0].duration == 1 }
    }
}
