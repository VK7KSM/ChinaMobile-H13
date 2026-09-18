cmake_minimum_required(VERSION 3.22.1)
project(h13_dmr_tx LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(IMBE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vendor/imbe_vocoder)
set(AMBE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vendor/ambe)
set(MBELIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vendor/mbelib)

add_library(h13_ambe SHARED
    software_ambe_jni.cpp
    ${IMBE_DIR}/aux_sub.cc
    ${IMBE_DIR}/basicop2.cc
    ${IMBE_DIR}/ch_decode.cc
    ${IMBE_DIR}/ch_encode.cc
    ${IMBE_DIR}/dc_rmv.cc
    ${IMBE_DIR}/decode.cc
    ${IMBE_DIR}/dsp_sub.cc
    ${IMBE_DIR}/encode.cc
    ${IMBE_DIR}/imbe_vocoder.cc
    ${IMBE_DIR}/imbe_vocoder_impl.cc
    ${IMBE_DIR}/math_sub.cc
    ${IMBE_DIR}/pe_lpf.cc
    ${IMBE_DIR}/pitch_est.cc
    ${IMBE_DIR}/pitch_ref.cc
    ${IMBE_DIR}/qnt_sub.cc
    ${IMBE_DIR}/rand_gen.cc
    ${IMBE_DIR}/sa_decode.cc
    ${IMBE_DIR}/sa_encode.cc
    ${IMBE_DIR}/sa_enh.cc
    ${IMBE_DIR}/tbls.cc
    ${IMBE_DIR}/uv_synt.cc
    ${IMBE_DIR}/v_synt.cc
    ${IMBE_DIR}/v_uv_det.cc
    ${MBELIB_DIR}/mbelib.c
    ${MBELIB_DIR}/ambe3600x2400.c
    ${MBELIB_DIR}/ambe3600x2450.c
    ${MBELIB_DIR}/ecc.c
    ${MBELIB_DIR}/imbe7100x4400.c
    ${MBELIB_DIR}/imbe7200x4400.c
    ${AMBE_DIR}/mbeenc.cc
    ${AMBE_DIR}/ambe.c
    ${AMBE_DIR}/Golay24128.cpp
    ${AMBE_DIR}/ambe_wrapper.cpp)

target_include_directories(h13_ambe PRIVATE ${IMBE_DIR} ${AMBE_DIR} ${MBELIB_DIR})
target_compile_definitions(h13_ambe PRIVATE _USE_MATH_DEFINES)
target_compile_options(h13_ambe PRIVATE -O2 -w)
target_link_libraries(h13_ambe PRIVATE log m)
