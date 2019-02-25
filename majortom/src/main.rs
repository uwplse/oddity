use log::{info, trace};
use clap::{App, Arg};

use majortom::{majortom, config, setup_logging};

fn main() {
    setup_logging();

    // parse CLI args
    let matches = App::new("majortom")
        .version("0.1")
        .author("Doug Woos <dwoos@cs.washington.edu>")
        .about("Oddity driver")
        .arg(Arg::with_name("CONFIG")
             .help("Sets the toml-formatted config file to use")
             .required(true)
             .index(1)).get_matches();

    // read config file
    let config = config::read(matches.value_of("CONFIG").unwrap()).expect("error reading config");
    info!("Started");
    trace!("Using config file {:?}", config);
    majortom(config).expect("Error")
}
