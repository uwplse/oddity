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
                self._state = {}
                self.start_handler(self._name, ret)
            elif msg['msgtype'] == 'quit':
                break
            self._state = ret.state()
            send(self._sock, ret.finalize())

class MutexServer(Node):
    
    def start_handler(self, name, ret):
        ret.set(['queue'], [(0, 1)])
        for i in range(1, 4):
            if i != name:
                ret.set(['max', i], 1)
        ret.set(['clock'], 1)
        if name == 1:
            ret.set(['lock'], True)
            ret.set_timeout('release', 5)
        else:
            ret.set(['lock'], False)
            ret.set_timeout('request', 5)

    def try_get_lock(self, name, ret):
        queue = ret.get(['queue'])
        try:
            min_request = min(queue)
        except ValueError:
            return
        if min_request[1] != name:
            return
        for i in range(1, 4):
            if name == i:
                continue
            if i < name and ret.get(['max', i]) <= min_request[0]:
                return
            if i > name and ret.get(['max', i]) < min_request[0]:
                return
        # We have the lock
        ret.set(['lock'], True)
        ret.clear_timeout('request')
        ret.set_timeout('release', 5)
            
    def message_handler(self, to, sender, type, body, ret):
        ret.set(['max', sender], body['clock'])
        ret.set(['clock'], body['clock'] + 1)
        if type == 'req':
            ret.set(['queue'], ret.get(['queue']) + [(body['clock'], sender)])
            ret.send(sender, 'ack', {'clock': ret.get(['clock'])})
        if type == 'rel':
            queue = ret.get(['queue'])
            ret.set(['queue'], [r for r in queue if r[1] != sender])
        self.try_get_lock(to, ret)

    def timeout_handler(self, name, timeout, ret):
        ret.set(['clock'], ret.get(['clock']) +  1)
        if timeout == 'request':
            ret.set(['queue'], ret.get(['queue']) + [(ret.get(['clock']), name)])
            for i in range(1, 4):
                if i != name:
                    ret.send(i, 'req', {'clock': ret.get(['clock'])})
        if timeout == 'release':
            queue = ret.get(['queue'])
            ret.set(['lock'], False)
            ret.set(['queue'], [r for r in queue if r[1] != name])
            for i in range(1, 4):
                if i != name:
                    ret.send(i, 'rel', {'clock': ret.get(['clock'])})
            ret.clear_timeout('release')
            ret.set_timeout('request', 5)

if __name__ == '__main__':
    import threading
    one = threading.Thread(target=MutexServer, args=[1])
    two = threading.Thread(target=MutexServer, args=[2])
    three = threading.Thread(target=MutexServer, args=[3])
    one.start()
    two.start()
    three.start()
    one.join()
    two.join()
    three.join()
