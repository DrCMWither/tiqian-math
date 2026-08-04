cmake_minimum_required(VERSION 3.22.1)
project(tiqian_math_android LANGUAGES CXX)

find_package(freetype REQUIRED CONFIG)
find_package(harfbuzz REQUIRED CONFIG)

add_library(tiqian_math_android SHARED math_font_bridge.cpp)

target_compile_features(tiqian_math_android PRIVATE cxx_std_17)
target_compile_options(tiqian_math_android PRIVATE -Wall -Wextra -Werror)
get_target_property(FREETYPE_PREFAB_INCLUDE freetype::freetype INTERFACE_INCLUDE_DIRECTORIES)
target_include_directories(tiqian_math_android PRIVATE "${FREETYPE_PREFAB_INCLUDE}/freetype2")
target_link_libraries(
    tiqian_math_android
    PRIVATE
    freetype::freetype
    harfbuzz::harfbuzz
    android
    log
)
