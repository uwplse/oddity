
use std::io::Read;
use std::io::Write;

use serde_json as json;
use serde_json::json;
use serde::de::DeserializeOwned;
use serde::Serialize;
use byteorder::{NetworkEndian,ReadBytesExt,WriteBytesExt};
use std::net::TcpStream;
use failure::Error;
use crate::config::OddityConfig;
use crate::ptrace_handlers::Handlers;
use crate::data::*;

pub struct OddityConnection<'a> {
    connection: TcpStream,
    handlers: &'a mut Handlers
}

impl<'a> OddityConnection<'a> {
    pub fn new(config: OddityConfig, handlers: &'a mut Handlers) -> Self {
        let address = config.address.unwrap_or(String::from("localhost:4343"));
        if let Ok(connection) = TcpStream::connect(address) {
            Self {
                connection: connection,
                handlers: handlers
            }
        }
        else {
            panic!("Couldn't connect to Oddity server");
        }
    }

    fn read(&mut self) -> Result<json::Value, Error> {
        let size : i32 = self.connection.read_i32::<NetworkEndian>()?;
        let mut buf = vec![0u8; size as usize];
        self.connection.read_exact(&mut buf)?;
        let v: json::Value = json::from_slice(&buf)?;
        Ok(v)
    }

    fn read_typed<T>(&mut self) -> Result<T, Error> where T: DeserializeOwned
    {
        let size : i32 = self.connection.read_i32::<NetworkEndian>()?;
        let mut buf = vec![0u8; size as usize];
        self.connection.read_exact(&mut buf)?;
        let v: T = json::from_slice(&buf)?;
        Ok(v)
    }
    
    fn write(&mut self, v: json::Value) -> Result<(), Error> {
        let s : String = v.to_string();
        self.connection.write_i32::<NetworkEndian>(s.len() as i32)?;
        self.connection.write(s.as_bytes())?;
        Ok(())
    }

    fn write_typed<T>(&mut self, v: &T) -> Result<(), Error> where T: Serialize {
        let s : String = json::to_string(v)?;
        trace!("Writing json: {}", s);
        self.connection.write_i32::<NetworkEndian>(s.len() as i32)?;
        self.connection.write(s.as_bytes())?;
        Ok(())
    }
    
    pub fn run(&mut self) -> Result<(), Error> {
        let servers = self.handlers.servers();
        self.write(json!({
            "msgtype": "register",
            "names": servers
        }))?;
        let resp = self.read()?;
        if !resp["ok"].as_bool().expect("malformed response") {
            bail!("registration failed");
        }
        trace!("Connected to Oddity server");
        loop {
            let req: Request = self.read_typed()?;
            let mut response: Response = Response::new();
            trace!("Got request from Oddity server: {:?}", req);
            let quit = match req {
                Request::Start {to} => {
                    self.handlers.handle_start(to, &mut response);
                    false
                },
                Request::Message(m) => {
                    self.handlers.handle_message(m, &mut response);
                    false
                },
                Request::Timeout(t) => {
                    self.handlers.handle_timeout(t, &mut response);
                    false
                },
                Request::Quit {} => true
            };
            if quit {
                return Ok(());
            }
            trace!("Returning response to server: {:?}", response);
            self.write_typed(&response)?;
        }
    }
}
    
