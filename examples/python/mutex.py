from stateshim import Node, Shim

N_NODES = 3

class MutexServer(Node):
    
    def start_handler(self, name, ret):
        ret.state['queue'] = [(0, '1')]
        for i in range(1, N_NODES+1):
            i = str(i)
            if i != name:
                ret.state['max'] = {i: 1}
        ret.state['clock'] = 1
        if name == '1':
            ret.state['lock'] = True
            ret.set_timeout('release', {}, 5)
        else:
            ret.state['lock'] = False
            ret.set_timeout('request', {}, 5)

    def try_get_lock(self, name, ret):
        queue = ret.state['queue']
        try:
            min_request = min(queue)
        except ValueError:
            return
        if min_request[1] != name:
            return
        for i in range(1, N_NODES+1):
            i = str(i)
            if name == i:
                continue
            if i < name and ret.state['max'].get(i, 0) <= min_request[0]:
                return
            if i > name and ret.state['max'].get(i, 0) < min_request[0]:
                return
        # We have the lock
        ret.state['lock'] = True
        ret.clear_timeout('request', {})
        ret.set_timeout('release', {}, 5)
            
    def message_handler(self, to, sender, type, body, ret):
        ret.state['max'][sender] = body['clock']
        ret.state['clock'] = body['clock'] + 1
        if type == 'req':
            ret.state['queue'] = ret.state['queue'] + [(body['clock'], sender)]
            ret.send(sender, 'ack', {'clock': ret.state['clock']})
        if type == 'rel':
            queue = ret.state['queue']
            ret.state['queue'] = [r for r in queue if r[1] != sender]
        if not ret.state['lock']:
            self.try_get_lock(to, ret)

    def timeout_handler(self, name, type, body, ret):
        ret.state['clock'] = ret.state['clock'] +  1
        if type == 'request':
            ret.state['queue'] = ret.state['queue'] + [(ret.state['clock'], name)]
            for i in range(1, N_NODES+1):
                i = str(i)
                if i != name:
                    ret.send(i, 'req', {'clock': ret.state['clock']})
        if type == 'release':
            queue = ret.state['queue']
            ret.state['lock'] =False
            ret.state['queue'] = [r for r in queue if r[1] != name]
            for i in range(1, N_NODES+1):
                i = str(i)
                if i != name:
                    ret.send(i, 'rel', {'clock': ret.state['clock']})
            ret.clear_timeout('release', {})
            ret.set_timeout('request', {}, 5)

if __name__ == '__main__':
    sh = Shim()
    for n in range(1, N_NODES+1):
        sh.add_node(MutexServer, str(n))
    sh.run()
