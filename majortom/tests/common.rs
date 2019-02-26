use std::path::PathBuf;
use std::env::set_current_dir;
use std::process::{Command, Stdio};

use majortom::{config};

pub fn setup_example(example: &str) -> config::Config {
    let mut example_path = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    example_path.push("examples");
    example_path.push(example);
    if !example_path.is_dir() {
        panic!("No example named {}", example);
    }
    set_current_dir(example_path).expect("Couldn't change directories");
    let make_status = Command::new("make").stdout(Stdio::null()).status()
        .expect("Couldn't run make");
    if !make_status.success() {
        panic!("Failed to build example {}", example)
    }
    let config = config::read(&format!("{}.toml", example))
        .expect("Failed to read config file");
    config
}
