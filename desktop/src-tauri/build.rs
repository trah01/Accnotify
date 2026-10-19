fn main() {
    println!("cargo:rerun-if-changed=../../VERSION");
    let version = std::fs::read_to_string("../../VERSION").expect("无法读取根目录 VERSION");
    assert_eq!(version.trim(), env!("CARGO_PKG_VERSION"), "请先运行 node scripts/sync-version.mjs 同步客户端版本");
    tauri_build::build()
}
