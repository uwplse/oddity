use serde_json as json;

#[derive(Serialize, Deserialize, Debug)]
pub struct Message {
    pub from: String,
    pub to: String,
    #[serde(rename="type")]
    pub ty: String,
    pub body: json::Value,
    pub raw: Vec<u8>
}

#[derive(Serialize, Deserialize, Debug)]
pub struct Timeout {
    pub to: String,
    #[serde(rename="type")]
    pub ty: String,
    pub body: json::Value,
    pub raw: Vec<u8>
}


#[derive(Deserialize, Debug)]
#[serde(tag="msgtype")]
pub enum Request {
    #[serde(rename="start")]
    Start {
        to: String
    },
    #[serde(rename="msg")]
    Message(Message),
    #[serde(rename="timeout")]
    Timeout(Timeout),
    #[serde(rename="quit")]
    Quit {}
}

#[derive(Serialize, Debug)]
pub struct Response {
    #[serde(rename="send-messages")]
    pub messages: Vec<Message>,
    #[serde(rename="set-timeouts")]
    pub timeouts: Vec<Timeout>,
    #[serde(rename="cleared-timeouts")]
    pub cleared_timeouts: Vec<Timeout>
}

impl Response {
    pub fn new() -> Self {
        return Self {
            messages: Vec::new(),
            timeouts: Vec::new(),
            cleared_timeouts: Vec::new()
        }
    }
}
