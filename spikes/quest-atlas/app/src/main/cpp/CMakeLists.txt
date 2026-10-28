cmake_minimum_required(VERSION 3.22.1)
project(galileo_atlas LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(APP_GLUE_DIR ${ANDROID_NDK}/sources/android/native_app_glue)
add_library(native_app_glue STATIC ${APP_GLUE_DIR}/android_native_app_glue.c)
target_include_directories(native_app_glue PUBLIC ${APP_GLUE_DIR})

find_package(OpenXR REQUIRED CONFIG)

add_library(galileo_atlas SHARED main.cpp)
target_compile_options(galileo_atlas PRIVATE -Wall -Wextra -O3)
target_link_libraries(galileo_atlas PRIVATE native_app_glue OpenXR::openxr_loader android log EGL GLESv3 mediandk aaudio)
set_target_properties(galileo_atlas PROPERTIES LINK_FLAGS "-u ANativeActivity_onCreate")
