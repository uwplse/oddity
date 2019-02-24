use std::collections::HashMap;
use std::ffi::CString;
use nix::unistd::{Pid, fork, ForkResult,execv};
use nix::sys::ptrace;
use nix::sys::wait::{waitpid, WaitStatus};
use nix::sys::signal::{kill, Signal};
use nix::sys::socket::{AddressFamily, SockProtocol, SockType, sockaddr_storage, sockaddr_in, sockaddr_in6};
use libc::user_regs_struct;
use bincode::{serialize, deserialize};
use std::mem::size_of;

use crate::data;

#[derive(Debug, Clone)]
enum File {
    UDPSocket(Option<SocketAddress>),
    GlobalFile(String),
    LocalFile(String),
    Special
}

#[derive(Debug, Clone)]
struct TracedProcess {
    pid: Pid,
    files: HashMap<i32, File>
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
struct TracedProcessIdentifier {
    name: String,
    index: u32
}

impl TracedProcessIdentifier {
    fn main_process(name: String) -> Self {
        Self {name, index: 0}
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
struct TimeoutId {
    id: u64
}

impl TimeoutId {
    fn to_bytes(&self) -> Vec<u8> {
        let mut ret = Vec::new();
        ret.extend(&self.id.to_le_bytes());
        return ret;
    }

    fn from_bytes(bytes: &[u8]) -> Self {
        let mut id: u64 = 0;
        for (i, b) in bytes.iter().enumerate() {
            id += (*b as u64) << (i * 8);
        }
        Self {id}
    }
}
    
struct TimeoutIdGenerator {
    next_id: u64
}

impl TimeoutIdGenerator {
    fn new() -> Self {
        Self {next_id: 0}
    }

    fn next(&mut self) -> TimeoutId {
        TimeoutId {id: self.next_id}
    }
}

#[derive(Serialize, Deserialize)]
struct WireMessage {
    from: Option<SocketAddress>,
    to: SocketAddress,
    data: Vec<u8>
}

pub struct Handlers {
    nodes: HashMap<String, String>,
    procs: HashMap<TracedProcessIdentifier, TracedProcess>,
    message_waiting_procs: HashMap<SocketAddress, (TracedProcessIdentifier, Syscall)>,
    timeout_id_generator: TimeoutIdGenerator,
    timeout_waiting_procs: HashMap<TimeoutId, (TracedProcessIdentifier, Syscall)>,
    address_to_name: HashMap<SocketAddress, String>
}


#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
enum SocketAddress {
    // for now, we only care about the port.
    IPV4(u16), IPV6(u16)
}

#[derive(Debug, Clone)]
enum Syscall {
    Brk,
    MProtect,
    ArchPrctl,
    Access(String),
    Open(String),
    Fstat(i32, File),
    MMap(i32, Option<File>),
    MUnmap,
    Close(i32, File),
    Read(i32, File),
    UDPSocket,
    Bind(i32, SocketAddress),
    Write(i32, File),
    RecvFrom(i32, File),
    SigProcMask,
    SigAction,
    NanoSleep,
    SendTo(i32, File, SocketAddress, Vec<u8>)
}

#[derive(Debug, Clone, Copy)]
enum SyscallReturn {
    Success(i64), Failure(i64)
}

impl TracedProcess {
    fn new(program: String) -> Self {
        let args: Vec<&str> = program.split(" " ).collect();
        let program_name = args[0];
        let mut files = HashMap::new();
        //stdin, stdout, stderr
        for fd in 0..3 {
            files.insert(fd, File::Special);
        }
        let proc = match fork() {
            Ok(ForkResult::Parent {child, ..}) => {
                trace!("Started child with pid {}", child);
                Self {pid: child, files: files}
            },
            Ok(ForkResult::Child) => {
                ptrace::traceme().expect("couldn't call trace");
                let args_cstring: Vec<CString> = args.into_iter().map(
                    |s| CString::new(s).unwrap()).collect();
                execv(&CString::new(program_name).unwrap(), &args_cstring).expect("couldn't exec");
                unreachable!();
            },
            Err(_) => panic!("Couldn't fork")
        };
        proc.wait();
        proc
    }
    
    fn kill(&self) {
        kill(self.pid, Signal::SIGKILL).expect("problem killing process");
    }

    fn wait(&self) {
        let status = waitpid(self.pid, None);
        if let Ok(WaitStatus::Stopped(_, _)) = status {}
        else {
            panic!("Unexpected WaitStatus {:?}", status)
        }
    }

    fn run_until_syscall(&self) {
        ptrace::syscall(self.pid).expect("ptrace::syscall failed");
    }

    fn get_registers(&self) -> user_regs_struct {
        ptrace::getregs(self.pid).expect("Couldn't get regs")
    }

    fn set_registers(&self, regs: user_regs_struct) {
        ptrace::setregs(self.pid, regs);
    }

    // functions for reading


    fn read_data(&self, addr: u64, len: usize) -> Vec<u8> {
        let mut buf : [u8; size_of::<libc::c_long>()] = [0; size_of::<libc::c_long>()];
        let mut bytes : Vec<u8> = Vec::with_capacity(len);
        for i in 0..len {
            if i % size_of::<libc::c_long>() == 0 {
                // The use of to_ne_bytes rather than to_be_bytes is a little
                // counter-intuitive. The key is that we're reading a c_long (an
                // i64, that is) from the memory of the target process. We're
                // interested in the underlying bytes, which means that we're
                // interested in the c_long's representation *on this
                // architecture*.
                buf = ptrace::read(self.pid, (addr + (i as u64)) as ptrace::AddressType).expect("ptrace read failed").to_ne_bytes();
            }
            bytes.push(buf[i % size_of::<libc::c_long>()]);
            // addr incremented once for each *byte* read
        }
        bytes
    }

    fn read_string(&self, addr: u64) -> String {
        let mut buf : libc::c_long;
        let mut bytes : Vec<u8> = Vec::new();
        let mut addr = addr;
        'outer: loop {
            buf = ptrace::read(self.pid, addr as ptrace::AddressType).expect("ptrace read failed");
            let new_bytes = buf.to_ne_bytes();
            for b in new_bytes.iter() {
                if *b == 0 {
                    break 'outer;
                }
                bytes.push(*b);
                // addr incremented once for each *byte* read
                addr += 1;
            }
        }
        return String::from_utf8(bytes).expect("Failed to convert to rust string");
    }

    unsafe fn read<T>(&self, addr: u64) -> T where T: Copy {
        let mut buf : [u8; size_of::<libc::c_long>()] = [0; size_of::<libc::c_long>()];
        let mut bytes : Vec<u8> = Vec::new();
        let total = size_of::<T>();
        for i in 0..total {
            if i % size_of::<libc::c_long>() == 0 {
                buf = ptrace::read(self.pid, (addr + (i as u64)) as ptrace::AddressType).expect("ptrace read failed").to_ne_bytes();
            }
            bytes.push(buf[i % size_of::<libc::c_long>()]);
            // addr incremented once for each *byte* read
        }
        let t_slice = std::mem::transmute::<&[u8], &[T]>(bytes.as_slice());
        return t_slice[0].clone();
    }

    fn read_socket_address(&self, addr: u64, addrlen: usize) -> SocketAddress {
        unsafe {
            let sas: sockaddr_storage = self.read(addr);
            if sas.ss_family == (AddressFamily::Inet as u16) {
                if addrlen != size_of::<sockaddr_in>() {
                    panic!("Insufficient storage");
                }
                let sa: sockaddr_in = self.read(addr);
                SocketAddress::IPV4(sa.sin_port.to_be())
            } else {
                panic!("Unsupported address family")
            }
        }
    }

    fn write_data(&self, addr: u64, addrlen: usize, data: Vec<u8>) -> usize {
        let mut buf : [u8; size_of::<libc::c_long>()] = [0; size_of::<libc::c_long>()];
        let length = std::cmp::max(addrlen, data.len());
        for (i, b) in data.iter().enumerate() {
            buf[i % size_of::<libc::c_long>()] = *b;
            if ((i + 1) % size_of::<libc::c_long>() == 0) ||
                i + 1 == length {
                let word = libc::c_long::from_ne_bytes(buf);
                ptrace::write(self.pid, (addr + (i as u64)) as ptrace::AddressType,
                              word as *mut libc::c_void).expect("Failed to write");
            }
            // exit early if we're not iterating over whole vector
            if i + 1 == length {
                break;
            }
        }
        length
    }

    //TODO implement this
    fn write_socket_address(&self, addr_ptr: u64, addrlen: usize,
                            addr: Option<SocketAddress>) {
    }
    
    fn get_syscall(&self) -> Syscall {
        self.wait();
        let regs = self.get_registers();
        let call_number = regs.orig_rax;
        let call = match call_number {
            0 => { //read()
                let fd = regs.rdi as i32;
                let file = self.files.get(&fd).expect("read() called on bad file");
                Syscall::Read(fd, file.clone())
            },
            1 => { //write()
                let fd = regs.rdi as i32;
                let file = self.files.get(&fd).expect("write() called on bad file");
                Syscall::Write(fd, file.clone())
            }
            2 => { //open()
                let s = self.read_string(regs.rdi);
                Syscall::Open(s)
            }
            3 => { //close()
                let fd = regs.rdi as i32;
                let file = self.files.get(&fd).expect("close() called on bad file");
                Syscall::Close(fd, file.clone())
            }
            5 => { //fstat()
                let fd = regs.rdi as i32;
                let file = self.files.get(&fd).expect("fstat() called on bad file");
                Syscall::Fstat(fd, file.clone())
            }
            9 => { // mmap(), only supported for global files for now
                let fd = regs.r8 as i32;
                if fd < 0 {
                    Syscall::MMap(fd, None)
                } else {
                    let file = self.files.get(&fd).expect(
                        &format!("mmap() called on bad fd {}", fd));
                    Syscall::MMap(fd, Some(file.clone()))
                }
            },
            10 => Syscall::MProtect,
            11 => Syscall::MUnmap,
            12 => Syscall::Brk,
            // TODO: figure out if I actually need to deal with signals
            13 => Syscall::SigAction,
            14 => Syscall::SigProcMask,
            21 => { // access()
                let s = self.read_string(regs.rdi);
                Syscall::Access(s)
            },
            35 => Syscall::NanoSleep,
            41 => { // socket()
                let socket_family = regs.rdi as i32;
                let socket_type = regs.rsi as i32;
                let socket_protocol = regs.rdx as i32;
                // ensure this is a supported socket type
                if (socket_family == AddressFamily::Inet as i32 ||
                    socket_family == AddressFamily::Inet6 as i32) &&
                    socket_type == SockType::Datagram as i32 &&
                    socket_protocol == SockProtocol::Udp as i32 {
                    Syscall::UDPSocket
                }
                else {
                    panic!("Unsupported socket({}, {}, {})",
                           socket_family, socket_type, socket_protocol);
                }
            },
            44 => { // sendto()
                let fd = regs.rdi as i32;
                let sock = self.files.get(&fd).expect(
                    &format!("sendto() called on bad fd {}", fd));
                let socket_address = self.read_socket_address(regs.r8, regs.r9 as usize);
                let data = self.read_data(regs.rsi, regs.rdx as usize);
                Syscall::SendTo(fd, sock.clone(), socket_address, data)
            }
            45 => { // recvfrom()
                let fd = regs.rdi as i32;
                let sock = self.files.get(&fd).expect(
                    &format!("recvfrom() called on bad fd {}", fd));
                Syscall::RecvFrom(fd, sock.clone())
            }
            49 => { // bind()
                let fd = regs.rdi as i32;
                let socket_address = self.read_socket_address(regs.rsi, regs.rdx as usize);
                Syscall::Bind(fd, socket_address)
            }
            158 => Syscall::ArchPrctl,
            _ => panic!("Unsupported system call {} called by process {:?}",
                        call_number, self)
        };
        call
    }

    fn get_syscall_return(&mut self, call: Syscall) -> SyscallReturn {
        self.wait();
        let regs = self.get_registers();
        let sys_ret = regs.rax as i64;
        let ret = if sys_ret < 0 {
            SyscallReturn::Failure(sys_ret)
        } else {
            SyscallReturn::Success(sys_ret)
        };
        match (call, ret) {
            (Syscall::Open(filename), SyscallReturn::Success(fd)) => {
                let fd = fd as i32;
                // TODO: identify local files
                let file = File::GlobalFile(filename);
                trace!("Adding file {} -> {:?} to proc {:?}", fd, file, self);
                self.files.insert(fd, file);
            }
            (Syscall::Close(fd, _), SyscallReturn::Success(_)) => {
                trace!("Removing file {} from proc {:?}", fd, self);
                self.files.remove(&fd);
            }
            (Syscall::UDPSocket, SyscallReturn::Success(fd)) => {
                let fd = fd as i32;
                let file = File::UDPSocket(None);
                self.files.insert(fd, file);
            }
            (Syscall::Bind(fd, addr), SyscallReturn::Success(_)) => {
                let sock = self.files.get_mut(&fd).expect(
                    &format!("Bind on bad fd {}", fd));
                trace!("Binding {:?} to {:?}", sock, addr);
                let new_sock = match sock {
                    File::UDPSocket(_) => File::UDPSocket(Some(addr)),
                    _ => panic!("bind() on bad file {:?}", sock)
                };
                self.files.insert(fd, new_sock);
            }
            _ => ()
        };
        ret
    }

    fn stop_syscall(&mut self) {
        let mut regs = self.get_registers();
        regs.orig_rax = <u64>::max_value();
        self.set_registers(regs);
    }

    fn wake_from_stopped_call(&self, call: Syscall) {
        self.run_until_syscall();
        self.wait();
        // fake a good return value depending on the call
        let mut regs = self.get_registers();
        regs.rax = match call {
            Syscall::SendTo(_, _, _, data) =>
                data.len() as u64,
            _ => 0
        };
        self.set_registers(regs);
    }

    /// Write addr and data into process's memory to simulate a recvfrom
    fn recvfrom_return(&self, addr: Option<SocketAddress>, data: Vec<u8>) {
        // get relevant registers before syscall
        let regs = self.get_registers();
        let buffer_ptr = regs.rsi;
        let buffer_len = regs.rdx as usize;
        let addr_ptr = regs.r8;
        let addr_len = regs.r9 as usize;

        // run syscall (which is a no-op)
        self.run_until_syscall();
        self.wait();

        // write to process memory
        let written = self.write_data(buffer_ptr, buffer_len, data);
        if addr_ptr != 0 {
            self.write_socket_address(addr_ptr, addr_len, addr);
        }
        // return data len
        let mut regs = self.get_registers();
        regs.rax = written as u64;
    }
}

impl Handlers {
    pub fn new(nodes: HashMap<String, String>) -> Self {
        Self {nodes, procs: HashMap::new(), message_waiting_procs: HashMap::new(),
              timeout_id_generator: TimeoutIdGenerator::new(),
              timeout_waiting_procs: HashMap::new(),
              address_to_name: HashMap::new()}
    }

    pub fn servers(self: &Self) -> Vec<&str> {
        let mut res = Vec::new();
        res.extend(self.nodes.keys().map(|s| s.as_str()));
        res
    }

    /// Fills the response from any non-blocking syscalls made by proc procid
    ///
    /// Should be called after any outstanding syscalls have been
    /// processed--i.e., a syscall exit (or process start) is the most recent
    /// event
    fn fill_response(&mut self, procid: TracedProcessIdentifier, response: &mut data::Response) {
        let proc = self.procs.get_mut(&procid).expect(
            &format!("Bad process identifier {:?}", procid));
        loop {
            proc.run_until_syscall();
            let call = proc.get_syscall();
            trace!("Process {:?} called Syscall {:?}", proc, call);
            // check for unsupported calls and panic if necessary
            match &call {
                Syscall::Read(_, file) => {
                    match file {
                        File::GlobalFile(_) | File::Special => (),
                        _ => panic!("Unsupported read of file {:?}", file)
                    }
                }
                Syscall::Write(_, file) => {
                    match file {
                        File::Special => (),
                        _ => panic!("Unsupported write to file {:?}", file)
                    }
                }
                Syscall::MMap(_, Some(file)) => {
                    match file {
                        File::GlobalFile(_) => (),
                        _ => panic!("Unsupported mmap on file {:?}", file)
                    }
                },
                Syscall::RecvFrom(_, file) => {
                    match file {
                        File::UDPSocket(Some(_)) => (),
                        _ => panic!("Unsupported recvfrom on file {:?}", file)
                    }
                }
                Syscall::SendTo(_, file, _, _) => {
                    match file {
                        File::UDPSocket(_) => (),
                        _ => panic!("Unsupported sendto on file {:?}", file)
                    }
                }
                _ => ()
            }
            match &call {
                Syscall::Bind(_, addr) => {
                    self.address_to_name.insert(addr.clone(), procid.name.clone());
                }
                Syscall::RecvFrom(_, File::UDPSocket(Some(addr))) => {
                    proc.stop_syscall();
                    self.message_waiting_procs.insert(addr.clone(),
                                                      (procid.clone(), call.clone()));
                    // we're blocking, so we're done here
                    return;
                }
                Syscall::NanoSleep => {
                    proc.stop_syscall();
                    let timeout_id = self.timeout_id_generator.next();
                    self.timeout_waiting_procs.insert(timeout_id.clone(),
                                                      (procid.clone(), call.clone()));
                    let timeout = data::Timeout {
                        to: procid.name.clone(),
                        ty: "sleep()".to_string(),
                        body: json!(format!("Timeout {}", timeout_id.id)),
                        raw: timeout_id.to_bytes()
                    };
                    response.timeouts.push(timeout);
                    // we're blocking, so we're done here
                    return;
                }
                Syscall::SendTo(_, File::UDPSocket(from_addr), to_addr, data) => {
                    proc.stop_syscall();
                    let to = self.address_to_name.get(&to_addr).expect("sendto() to unknown address");
                    let raw = WireMessage {from: from_addr.clone(), to: to_addr.clone(), data: data.clone()};
                    let message = data::Message {
                        from: procid.name.clone(),
                        to: to.clone(),
                        ty: "Message".to_string(),
                        body: json!("A message".to_string()),
                        raw: serialize(&raw).unwrap()
                    };
                    response.messages.push(message);
                    // don't execute ordinary syscall return handling
                    proc.wake_from_stopped_call(call.clone());
                    continue;
                }
                _ => ()
            };
            proc.run_until_syscall();
            let ret = proc.get_syscall_return(call);
            trace!("Process {:?} got syscall return {:?}", proc, ret);
        }
    }
    
    pub fn handle_start(&mut self, node: String, response: &mut data::Response) {
        if !self.nodes.contains_key(&node) {
            panic!("Got bad node name {} from server", node);
        }
        let procid = TracedProcessIdentifier::main_process(node.clone());
        // kill proc if it's already started
        if let Some(p) = &self.procs.get(&procid) {
            p.kill();
        }
        let program = &self.nodes[&node];
        let proc = TracedProcess::new(program.to_string());
        self.procs.insert(procid.clone(), proc);
        self.fill_response(procid, response);
    }

    pub fn handle_message(&mut self, message: data::Message, response: &mut data::Response) {
        let node = message.to;
        let raw = message.raw;
        let wire_message : WireMessage = deserialize(&raw).expect("Deserialize failed");
        let (procid, call) = self.message_waiting_procs.get(&wire_message.to).expect("Received unrecognized message");
        debug_assert!(procid.name == node, "Message send to mismatched node");
        let proc = self.procs.get_mut(procid).expect("Bad procid");
        proc.recvfrom_return(wire_message.from, wire_message.data);
        self.fill_response(procid.clone(), response);
    }

    pub fn handle_timeout(&mut self, timeout: data::Timeout,
                          response: &mut data::Response) {
        let node = timeout.to;
        let raw = timeout.raw;
        let timeout_id = TimeoutId::from_bytes(&raw);
        let (procid, call) = self.timeout_waiting_procs.get(&timeout_id).expect("Received unrecognized timeout");
        debug_assert!(procid.name == node, "Timeout sent to mismatched node");
        let proc = self.procs.get_mut(procid).expect("Bad procid");
        proc.wake_from_stopped_call(call.clone());
        // always clear timeout
        // don't need raw field on cleared timeout
        response.cleared_timeouts.push(data::Timeout {
            to: node,
            ty: timeout.ty,
            body: timeout.body,
            raw: Vec::new()
        });
        self.fill_response(procid.clone(), response);
    }

}
