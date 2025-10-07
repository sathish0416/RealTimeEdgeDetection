cmake_minimum_required(VERSION 3.22.1)
project(edge_detection_native)

# Update this path to your local OpenCV Android SDK location
# Use forward slashes on Windows
set(OpenCV_DIR "D:/SRMAP/softwares/Downloads/opencv-4.12.0-android-sdk/OpenCV-android-sdk/sdk/native/jni")

find_package(OpenCV REQUIRED)
message(STATUS "OpenCV libraries: ${OpenCV_LIBS}")
message(STATUS "OpenCV include dirs: ${OpenCV_INCLUDE_DIRS}")

add_library(native-lib SHARED
    native-lib.cpp
)

# NDK log library
find_library(log-lib log)

# Include directories and link libraries
target_include_directories(native-lib PRIVATE ${OpenCV_INCLUDE_DIRS})

target_link_libraries(
    native-lib
    ${OpenCV_LIBS}
    ${log-lib}
)
