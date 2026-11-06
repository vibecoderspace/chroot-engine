cmake_minimum_required(VERSION 3.22)
project(termux C)

add_library(termux SHARED termux.c)

target_compile_features(termux PRIVATE c_std_11)
target_compile_options(termux PRIVATE -Wall -Wextra)
target_link_libraries(termux log)
set_target_properties(termux PROPERTIES
    C_VISIBILITY_PRESET default
    POSITION_INDEPENDENT_CODE ON)
