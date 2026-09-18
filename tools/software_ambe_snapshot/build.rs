// build.rs - Build script for compiling AMBE encoder

use std::env;
use std::path::PathBuf;

fn main() {
    let manifest_dir = env::var("CARGO_MANIFEST_DIR").unwrap();
    let imbe_dir = PathBuf::from(&manifest_dir).join("libs/imbe_vocoder");
    let ambe_dir = PathBuf::from(&manifest_dir).join("libs/ambe");
    let mbelib_dir = PathBuf::from(&manifest_dir).join("libs/mbelib");

    // Detect MSVC vs GCC/Clang
    let is_msvc = env::var("TARGET").map(|t| t.contains("msvc")).unwrap_or(false);

    // Compile imbe_vocoder library
    let mut imbe_build = cc::Build::new();
    imbe_build
        .cpp(true)
        .opt_level(2)
        .warnings(false)
        .include(&imbe_dir);

    // Set C++ standard based on compiler
    if is_msvc {
        imbe_build.flag("/std:c++14");
    } else {
        imbe_build.flag("-std=c++11").flag("-w");
    }

    // Add all imbe_vocoder source files
    let imbe_sources = [
        "aux_sub.cc",
        "basicop2.cc",
        "ch_decode.cc",
        "ch_encode.cc",
        "dc_rmv.cc",
        "decode.cc",
        "dsp_sub.cc",
        "encode.cc",
        "imbe_vocoder.cc",
        "imbe_vocoder_impl.cc",
        "math_sub.cc",
        "pe_lpf.cc",
        "pitch_est.cc",
        "pitch_ref.cc",
        "qnt_sub.cc",
        "rand_gen.cc",
        "sa_decode.cc",
        "sa_encode.cc",
        "sa_enh.cc",
        "tbls.cc",
        "uv_synt.cc",
        "v_synt.cc",
        "v_uv_det.cc",
    ];

    for source in &imbe_sources {
        let path = imbe_dir.join(source);
        if path.exists() {
            imbe_build.file(&path);
        }
    }

    imbe_build.compile("imbe_vocoder");

    // Compile mbelib library
    let mut mbelib_build = cc::Build::new();
    mbelib_build
        .opt_level(2)
        .warnings(false)
        .define("_USE_MATH_DEFINES", None)
        .include(&mbelib_dir);

    if !is_msvc {
        mbelib_build.flag("-w");
    }

    // mbelib source files
    let mbelib_sources = [
        "mbelib.c",
        "ambe3600x2400.c",
        "ambe3600x2450.c",
        "ecc.c",
        "imbe7100x4400.c",
        "imbe7200x4400.c",
    ];

    for source in &mbelib_sources {
        mbelib_build.file(mbelib_dir.join(source));
    }

    mbelib_build.compile("mbelib");

    // Compile AMBE encoder library
    let mut ambe_build = cc::Build::new();
    ambe_build
        .cpp(true)
        .opt_level(2)
        .warnings(false)
        .define("_USE_MATH_DEFINES", None)
        .include(&imbe_dir)
        .include(&ambe_dir)
        .include(&mbelib_dir);

    // Set C++ standard based on compiler
    if is_msvc {
        ambe_build.flag("/std:c++14");
    } else {
        ambe_build.flag("-std=c++11").flag("-w");
    }

    // AMBE encoder source files
    ambe_build.file(ambe_dir.join("mbeenc.cc"));
    ambe_build.file(ambe_dir.join("ambe.c"));
    ambe_build.file(ambe_dir.join("Golay24128.cpp"));
    ambe_build.file(ambe_dir.join("ambe_wrapper.cpp"));

    ambe_build.compile("ambe_encoder");

    // Link libraries
    println!("cargo:rustc-link-lib=static=imbe_vocoder");
    println!("cargo:rustc-link-lib=static=mbelib");
    println!("cargo:rustc-link-lib=static=ambe_encoder");

    // Rerun if source files change
    println!("cargo:rerun-if-changed=libs/imbe_vocoder");
    println!("cargo:rerun-if-changed=libs/ambe");
    println!("cargo:rerun-if-changed=libs/mbelib");
    println!("cargo:rerun-if-changed=build.rs");
}
