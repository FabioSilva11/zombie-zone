cmake_minimum_required(VERSION 3.22.1)
project(zombiezone LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(zombiezone SHARED
    jni_bridge.cpp
    core/json.cpp
    core/platform.cpp
    world/block_textures.cpp
    world/terrain.cpp
    world/world.cpp
    render/character.cpp
    render/gl_util.cpp
    render/renderer.cpp
    game/game.cpp
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
