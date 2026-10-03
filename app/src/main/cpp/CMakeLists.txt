cmake_minimum_required(VERSION 3.22.1)
project(zombiezone LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# PC de desenvolvimento com 4 GB de RAM: um clang por vez (o ninja usaria um por núcleo).
set_property(GLOBAL PROPERTY JOB_POOLS compile_pool=1 link_pool=1)
set(CMAKE_JOB_POOL_COMPILE compile_pool)
set(CMAKE_JOB_POOL_LINK link_pool)

add_library(zombiezone SHARED
    jni_bridge.cpp
    core/json.cpp
    core/platform.cpp
    world/block_textures.cpp
    world/buildings.cpp
    world/island.cpp
    world/structures.cpp
    world/terrain.cpp
    world/world.cpp
    render/character.cpp
    render/gl_util.cpp
    render/renderer.cpp
    game/game.cpp
    game/game_items.cpp
    game/game_mission.cpp
    game/game_survival.cpp
    game/game_zombies.cpp
    game/nav.cpp
    game/net.cpp
)

# Sem -ffast-math: o mundo PvP é gerado em cada aparelho a partir da mesma semente
# e precisa sair idêntico em todos eles.
target_compile_options(zombiezone PRIVATE -Wall -Wextra -Wno-unused-parameter -O2)

target_link_libraries(zombiezone
    android
    log
    GLESv3
)
