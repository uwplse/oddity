import threading
import socket
import json
import struct
from copy import deepcopy

class HandlerReturn(object):

    def __init__(self, name, state):
        self._name = name
        self._messages = []
        self._updates = []
        self._state = deepcopy(state)
        self._timeouts = []
        self._cleared_timeouts = []

    def path_of(self, p):
        if isinstance(p, basestring):
            return [p]
        return p

    def get(self, path):
        path = self.path_of(path)
        v = self._state
        for p in path:
            try:
                v = v[p]
            except:
                return None
        return v

    
    def set(self, path, value):
        path = self.path_of(path)
        self._updates.append({"path": path, "value": value})
        if not path:
            self._state = value
            return value
        v = self._state
        last_key = path[-1]
        for p in path[:-1]:
            try:
                v = v[p]
            except:
                v[p] = {}
                v = v[p]
        v[last_key] = value
        return value

    def send(self, dst, type, body):
        self._messages.append({'from': self._name, 'to': dst, 'type': type, 'body': body})
            
    def set_timeout(self, type, body, seconds=5):
        self._timeouts.append({'to': self._name, 'type': type, 'body': body})

    def clear_timeout(self, type, body):
        self._cleared_timeouts.append({'to': self._name, 'type': type, 'body': body})

    def finalize(self):
        return {'state-updates': self._updates, 'send-messages': self._messages,
                'set-timeouts': self._timeouts, 'cleared-timeouts': self._cleared_timeouts}

    def state(self):
        return self._state
    
def send(sock, obj):
    s = json.dumps(obj)
    print s
    length = struct.pack('!I', len(s))
    sock.sendall(length+s)

def recv(sock):
    buf = ''
    while len(buf) < 4:
        buf += sock.recv(4-len(buf))
    length = struct.unpack('!i', buf[:4])[0]
    buf = ''
    while len(buf) < length:
        buf += sock.recv(length-len(buf))
    return json.loads(buf)
        
class Node(object):

    def __init__(self, name, raddr='localhost', rport=4343, cfg={}):
        self._name = name
        self._state = {}
        self._cfg = cfg
        self._register(raddr, rport)
        self._event_loop()

    def config(self):
        return self._cfg
        
    
    def start_handler(self, name, ret):
        pass

    def message_handler(self, to, sender, type, body, ret):
        pass

    def timeout_handler(self, name, type, body, ret):
        pass

    def _register(self, raddr, rport):
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._sock.connect((raddr, rport))
        send(self._sock, {'msgtype': 'register', 'name': self._name})
        resp = recv(self._sock)
        if not resp.get('ok'):
            raise Exception('Oh no')
        print "Registered"

    def _respond(self, ret):
        self._state = ret.state()
        send(self._sock, ret.finalize())

    def _event_loop(self):
        while True:
            msg = recv(self._sock)
            ret = HandlerReturn(self._name, self._state)
            if msg['msgtype'] == 'msg':
                self.message_handler(self._name, msg['from'], msg['type'], msg['body'], ret)
            elif msg['msgtype'] == 'timeout':
                self.timeout_handler(self._name, msg['type'], msg['body'], ret)
            elif msg['msgtype'] == 'start':
                self._state = {}
                self.start_handler(self._name, ret)
            elif msg['msgtype'] == 'quit':
                break
            self._state = ret.state()
            send(self._sock, ret.finalize())

class Shim(object):
    def __init__(self):
        self.threads = []

    def add_node(self, cls, *args, **kwargs):
        self.threads.append(threading.Thread(target=cls, args=args, kwargs=kwargs))

    def run(self):
        for thr in self.threads:
            thr.start()
        for thr in self.threads:
            thr.join()
