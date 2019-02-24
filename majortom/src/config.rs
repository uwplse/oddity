extern crate toml;
extern crate serde;
extern crate serde_json;

use std::collections::HashMap;
use std::fs;
use std::error::Error;


#[derive(Deserialize, Debug)]
pub struct OddityConfig {
    pub address: Option<String>,
}

#[derive(Deserialize, Debug)]
pub struct Config {
    pub oddity: OddityConfig,
    pub nodes: HashMap<String, String>
}

pub fn read(filename: &str) -> Result<Config, Box<dyn Error>> {
    let contents = fs::read_to_string(filename)?;
    
    let config: Config = toml::from_str(&contents)?;
    Ok(config)
}
