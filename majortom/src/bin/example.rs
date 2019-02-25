use std::path::PathBuf;
use std::env::set_current_dir;
use std::process::Command;

use clap::{App,Arg};

use majortom::{config, majortom, setup_logging};

fn main() {
    let matches = App::new("majortom-example")
        .version("0.1")
        .author("Doug Woos <dwoos@cs.washington.edu>")
        .about("Example driver")
        .arg(Arg::with_name("EXAMPLE")
             .help("The example system to run")
             .required(true)
             .index(1)).get_matches();

    let example = matches.value_of("EXAMPLE").unwrap();
    let mut example_path = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    example_path.push("examples");
    example_path.push(example);
    if !example_path.is_dir() {
        panic!("No example named {}", example);
    }
    set_current_dir(example_path).expect("Couldn't change directories");
    let make_status = Command::new("make").status().expect("Couldn't run make");
    if !make_status.success() {
        panic!("Failed to build example {}", example)
    }
    let config = config::read(&format!("{}.toml", example))
        .expect("Failed to read config file");
    setup_logging();
    majortom(config).expect("Error");
}
