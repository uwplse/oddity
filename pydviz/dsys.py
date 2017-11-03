import socket
import json
import struct
from copy import deepcopy

class HandlerReturn(object):

    def __init__(self, state):
        self._messages = []
        self._updates = []
        self._state = deepcopy(state)
        self._timeouts = {}
        self._cleared_timeouts = []

    def get(self, path):
        v = self._state
        for p in path:
            try:
                v = v[p]
            except:
                return None
        return v

    def set(self, path, value):
        self._updates.append({"path": path, "value": value})
        if not path:
            self._state = value
            return
        v = self._state
        last_key = path[-1]
        for p in path[:-1]:
            try:
                v = v[p]
            except:
                v[p] = {}
                v = v[p]
        v[last_key] = value

    def send(self, dst, type, body):
        self._messages.append({'dst': dst, 'type': type, 'body': body})
            
    def set_timeout(self, name, seconds):
        self._timeouts[name] = seconds

    def clear_timeout(self, name):
        self._cleared_timeouts.append(name)

    def finalize(self):
        return {'state-updates': self._updates, 'messages': self._messages,
                'timeouts': self._timeouts, 'cleared-timeouts': self._cleared_timeouts}

    def state(self):
        return self._state
def send(sock, obj):
    s = json.dumps(obj)
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

    def __init__(self, name, raddr='localhost', rport=4343):
        self._name = name
        self._state = {}
        self._register(raddr, rport)
        self._event_loop()
        
    
    def start_handler(self, name, ret):
        pass

    def message_handler(self, to, sender, type, body, ret):
        pass

    def timeout_handler(self, name, timeout, ret):
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
            ret = HandlerReturn(self._state)
            if msg['msgtype'] == 'msg':
                self.message_handler(self._name, msg['from'], msg['type'], msg['body'], ret)
            elif msg['msgtype'] == 'timeout':
                self.timeout_handler(self._name, msg['timeout'], ret)
            elif msg['msgtype'] == 'start':
                self.start_handler(self._name, ret)
            elif msg['msgtype'] == 'quit':
                break
            self._state = ret.state()
            send(self._sock, ret.finalize())

class PingPongServer(Node):
    
    def start_handler(self, name, ret):
        if name == 'pinger':
            ret.set(['pongs'], 0)
            ret.set_timeout('send-ping', 5)
        elif name == 'ponger':
            ret.set(['pings'], 0)

    def message_handler(self, to, sender, type, body, ret):
        if type == 'ping':
            pings = ret.get(['pings'])
            pings = pings + body['count']
            ret.set(['pings'], pings)
            ret.send(sender, 'pong', {'count': pings})
        elif type == 'pong':
            pongs = ret.get(['pongs'])
            pongs = pongs + body['count']
            ret.set(['pongs'], pings)
            ret.set_timeout('send-ping', 5)

    def timeout_handler(self, timeout, ret):
        ret.clear_timeout(timeout)
        ret.send('ponger', 'ping', {'count': ret.get(['pings'])})

if __name__ == '__main__':
    import threading
    pinger = threading.Thread(target=PingPongServer, args=['pinger'])
    ponger = threading.Thread(target=PingPongServer, args=['ponger'])
    pinger.start()
    ponger.start()
    pinger.join()
    ponger.join()
