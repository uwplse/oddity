extern crate clap;
extern crate toml;

#[allow(unused_imports)]
#[macro_use]
extern crate serde_derive;
extern crate serde;
#[macro_use]
extern crate serde_json;
extern crate bincode;
extern crate byteorder;
#[macro_use]
extern crate failure;
#[macro_use]
extern crate log;
extern crate fern;
extern crate chrono;
extern crate libc;


mod config;
mod oddity;
mod ptrace_handlers;
mod data;

use clap::{App, Arg};
use ptrace_handlers::Handlers;
use oddity::OddityConnection;

fn main() {
    // set up logging
    fern::Dispatch::new()
        .chain(
            fern::Dispatch::new()
                .format(|out, message, record| {
                    out.finish(format_args!(
                        "[{}] {}",
                        record.level(),
                        message))
                })
                .level(log::LevelFilter::Trace)
                .chain(std::io::stdout()))
        .chain(
            fern::Dispatch::new()
                .format(|out, message, record| {
                    out.finish(format_args!(
                        "[{}] [{}] [{}]\n\t{}",
                        chrono::Local::now().format("%Y-%m-%d %H:%M:%S"),
                        record.level(),
                        record.target(),
                        message))
                })
                .level(log::LevelFilter::Trace)
                .chain(fern::log_file("majortom.log").expect("couldn't open log file")))
        .apply().expect("Error setting up logging");

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

    // set up ptrace handlers
    let mut handlers = Handlers::new(config.nodes);

    // set up oddity connection
    let mut oddity = OddityConnection::new(config.oddity, &mut handlers);

    oddity.run().expect("Error");
}
