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
//#[macro_use]
//extern crate failure_derive;
#[macro_use]
extern crate log;
extern crate fern;
extern crate chrono;
extern crate libc;


pub mod config;
pub mod oddity;
pub mod ptrace_handlers;
pub mod data;

pub fn majortom(config: config::Config) -> Result<(), failure::Error> {
    trace!("Running with config {:?}", config);
    let mut handlers = ptrace_handlers::Handlers::new(config.nodes);
    
    // set up oddity connection
    let mut oddity = oddity::OddityConnection::new(config.oddity, &mut handlers)?;
    oddity.run()?;
    Ok(())
}

pub fn setup_logging() {
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
}
