use majortom::ptrace_handlers;
use majortom::data;

mod common;

#[test]
fn test_simple() {
    let config = common::setup_example("simple");
    let mut handlers = ptrace_handlers::Handlers::new(config.nodes);
    let mut response = data::Response::new();
    handlers.handle_start("pinger".to_string(), &mut response).unwrap();
    assert_eq!(response.timeouts.len(), 1);
    assert_eq!(response.messages.len(), 0);
    assert_eq!(response.cleared_timeouts.len(), 0);
    let timeout = response.timeouts.pop().unwrap();
    response = data::Response::new();
    handlers.handle_start("ponger".to_string(), &mut response).unwrap();
    assert_eq!(response.timeouts.len(), 0);
    assert_eq!(response.messages.len(), 0);
    assert_eq!(response.cleared_timeouts.len(), 0);
    response = data::Response::new();
    handlers.handle_timeout(timeout, &mut response).unwrap();
    assert_eq!(response.timeouts.len(), 0);
    assert_eq!(response.messages.len(), 1);
    assert_eq!(response.cleared_timeouts.len(), 1);
    let mut message = response.messages.pop().unwrap();
    for _ in 0..10 { // could do this forever!
        response = data::Response::new();
        handlers.handle_message(message, &mut response).unwrap();
        assert_eq!(response.timeouts.len(), 0);
        assert_eq!(response.messages.len(), 1);
        assert_eq!(response.cleared_timeouts.len(), 0);
        message = response.messages.pop().unwrap();
    }

    // restart, see that it sets a timeout again
    handlers.handle_start("pinger".to_string(), &mut response).unwrap();
    assert_eq!(response.timeouts.len(), 1);
    assert_eq!(response.messages.len(), 0);
    assert_eq!(response.cleared_timeouts.len(), 0);
}
