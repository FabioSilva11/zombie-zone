// Tipos de bloco do mundo estilo Minecraft e camadas de textura procedural.
#pragma once

#include <cstdint>

namespace zz {

enum TexLayer : uint8_t {
    T_GRASS_TOP, T_GRASS_SIDE, T_DIRT, T_STONE, T_COBBLE, T_SAND, T_SANDSTONE_SIDE, T_SANDSTONE_TOP,
    T_SNOW, T_SNOW_SIDE, T_ICE, T_WATER, T_LOG_OAK, T_LOG_TOP, T_LEAVES_OAK, T_LOG_SPRUCE,
    T_LEAVES_SPRUCE, T_LOG_DEAD, T_PLANKS, T_CRATE, T_CACTUS_SIDE, T_CACTUS_TOP, T_GRAVEL, T_PATH_TOP,
    T_PATH_SIDE, T_OBSIDIAN, T_PORTAL, T_NETHERRACK, T_ASH_TOP, T_ASH_SIDE, T_BEDROCK, T_LANTERN,
    T_MAGMA, T_TERRACOTTA, T_HAY_SIDE, T_HAY_TOP, T_COAL_ORE, T_IRON_ORE, T_CAMPFIRE_TOP, T_CAMPFIRE_SIDE,
    T_FLOWER_RED, T_FLOWER_YELLOW, T_TALL_GRASS, T_DEAD_BUSH, T_MUSHROOM_RED, T_DIAMOND, T_GOLD,
    T_COUNT
};

enum Block : uint8_t {
    B_AIR = 0, B_GRASS, B_DIRT, B_STONE, B_COBBLE, B_SAND, B_SANDSTONE, B_SNOW_GRASS, B_SNOW, B_ICE,
    B_WATER, B_LOG_OAK, B_LEAVES_OAK, B_LOG_SPRUCE, B_LEAVES_SPRUCE, B_LOG_DEAD, B_PLANKS, B_CRATE,
    B_CACTUS, B_GRAVEL, B_PATH, B_OBSIDIAN, B_PORTAL, B_NETHERRACK, B_ASH, B_BEDROCK, B_LANTERN,
    B_MAGMA, B_TERRACOTTA, B_HAY, B_COAL_ORE, B_IRON_ORE, B_CAMPFIRE, B_FLOWER_RED, B_FLOWER_YELLOW,
    B_TALL_GRASS, B_DEAD_BUSH, B_MUSHROOM_RED,
    B_COUNT
};

enum class Shape : uint8_t { None, Cube, Cross, Liquid };

struct BlockInfo {
    Shape shape;
    bool solid;     // participa da colisão
    bool opaque;    // esconde faces vizinhas
    bool emissive;  // ignora a luz do ambiente (lanterna, magma, portal)
    uint8_t top, side, bottom;
    uint32_t mapColor;  // cor 0xRRGGBB do minimapa
};

inline const BlockInfo& blockInfo(uint8_t id) {
    static const BlockInfo kBlocks[B_COUNT] = {
        {Shape::None, false, false, false, 0, 0, 0, 0x000000},                                      // AIR
        {Shape::Cube, true, true, false, T_GRASS_TOP, T_GRASS_SIDE, T_DIRT, 0x5fa83a},              // GRASS
        {Shape::Cube, true, true, false, T_DIRT, T_DIRT, T_DIRT, 0x8a5a36},                         // DIRT
        {Shape::Cube, true, true, false, T_STONE, T_STONE, T_STONE, 0x8a8a8a},                      // STONE
        {Shape::Cube, true, true, false, T_COBBLE, T_COBBLE, T_COBBLE, 0x6f6f6f},                   // COBBLE
        {Shape::Cube, true, true, false, T_SAND, T_SAND, T_SAND, 0xe3d29a},                         // SAND
        {Shape::Cube, true, true, false, T_SANDSTONE_TOP, T_SANDSTONE_SIDE, T_SANDSTONE_TOP, 0xd8c486},
        {Shape::Cube, true, true, false, T_SNOW, T_SNOW_SIDE, T_DIRT, 0xf0f6fa},                    // SNOW_GRASS
        {Shape::Cube, true, true, false, T_SNOW, T_SNOW, T_SNOW, 0xf4f8fb},                         // SNOW
        {Shape::Cube, true, true, false, T_ICE, T_ICE, T_ICE, 0x9cc4f2},                            // ICE
        {Shape::Liquid, false, false, false, T_WATER, T_WATER, T_WATER, 0x3a6fd8},                  // WATER
        {Shape::Cube, true, true, false, T_LOG_TOP, T_LOG_OAK, T_LOG_TOP, 0x6b4e2e},                // LOG_OAK
        {Shape::Cube, true, true, false, T_LEAVES_OAK, T_LEAVES_OAK, T_LEAVES_OAK, 0x3f8a2a},       // LEAVES_OAK
        {Shape::Cube, true, true, false, T_LOG_TOP, T_LOG_SPRUCE, T_LOG_TOP, 0x4a3420},             // LOG_SPRUCE
        {Shape::Cube, true, true, false, T_LEAVES_SPRUCE, T_LEAVES_SPRUCE, T_LEAVES_SPRUCE, 0x2f5e3a},
        {Shape::Cube, true, true, false, T_LOG_TOP, T_LOG_DEAD, T_LOG_TOP, 0x5a5048},               // LOG_DEAD
        {Shape::Cube, true, true, false, T_PLANKS, T_PLANKS, T_PLANKS, 0xb48a52},                   // PLANKS
        {Shape::Cube, true, true, false, T_CRATE, T_CRATE, T_CRATE, 0x9c7040},                      // CRATE
        {Shape::Cube, true, true, false, T_CACTUS_TOP, T_CACTUS_SIDE, T_CACTUS_TOP, 0x3e8a35},      // CACTUS
        {Shape::Cube, true, true, false, T_GRAVEL, T_GRAVEL, T_GRAVEL, 0x8c8480},                   // GRAVEL
        {Shape::Cube, true, true, false, T_PATH_TOP, T_PATH_SIDE, T_DIRT, 0xa9874f},                // PATH
        {Shape::Cube, true, true, false, T_OBSIDIAN, T_OBSIDIAN, T_OBSIDIAN, 0x241a33},             // OBSIDIAN
        {Shape::None, false, false, true, T_PORTAL, T_PORTAL, T_PORTAL, 0x9a4cff},                  // PORTAL
        {Shape::Cube, true, true, false, T_NETHERRACK, T_NETHERRACK, T_NETHERRACK, 0x7a3434},       // NETHERRACK
        {Shape::Cube, true, true, false, T_ASH_TOP, T_ASH_SIDE, T_DIRT, 0x6b6052},                  // ASH
        {Shape::Cube, true, true, false, T_BEDROCK, T_BEDROCK, T_BEDROCK, 0x333333},                // BEDROCK
        {Shape::Cube, true, true, true, T_LANTERN, T_LANTERN, T_LANTERN, 0xffd27a},                 // LANTERN
        {Shape::Cube, true, true, true, T_MAGMA, T_MAGMA, T_MAGMA, 0xd2541c},                       // MAGMA
        {Shape::Cube, true, true, false, T_TERRACOTTA, T_TERRACOTTA, T_TERRACOTTA, 0xb5643f},       // TERRACOTTA
        {Shape::Cube, true, true, false, T_HAY_TOP, T_HAY_SIDE, T_HAY_TOP, 0xd6b23c},               // HAY
        {Shape::Cube, true, true, false, T_COAL_ORE, T_COAL_ORE, T_COAL_ORE, 0x777777},             // COAL_ORE
        {Shape::Cube, true, true, false, T_IRON_ORE, T_IRON_ORE, T_IRON_ORE, 0x8d8580},             // IRON_ORE
        {Shape::Cube, true, true, true, T_CAMPFIRE_TOP, T_CAMPFIRE_SIDE, T_LOG_TOP, 0xff8a2a},      // CAMPFIRE
        {Shape::Cross, false, false, false, T_FLOWER_RED, T_FLOWER_RED, T_FLOWER_RED, 0x5fa83a},
        {Shape::Cross, false, false, false, T_FLOWER_YELLOW, T_FLOWER_YELLOW, T_FLOWER_YELLOW, 0x5fa83a},
        {Shape::Cross, false, false, false, T_TALL_GRASS, T_TALL_GRASS, T_TALL_GRASS, 0x5fa83a},
        {Shape::Cross, false, false, false, T_DEAD_BUSH, T_DEAD_BUSH, T_DEAD_BUSH, 0xd8c486},
        {Shape::Cross, false, false, false, T_MUSHROOM_RED, T_MUSHROOM_RED, T_MUSHROOM_RED, 0x6b6052},
    };
    return kBlocks[id < B_COUNT ? id : 0];
}

inline bool isSolid(uint8_t id) { return blockInfo(id).solid; }
inline bool isOpaque(uint8_t id) { return blockInfo(id).opaque; }

}  // namespace zz
