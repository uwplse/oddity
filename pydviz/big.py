"""A Big System.

"""

import threading
from shim import Node, Shim

class BigServer(Node):
    def start_handler(self, name, ret):
        ret.set_timeout('Transmit', {})

    def broadcast(self, name, type, body, ret):
        for s in self.cluster(ret):
            if s != name:
                ret.send(s, type, body)

    def cluster(self, ret):
        return self.config()['cluster']

    def timeout_handler(self, name, type, body, ret):
        if type == 'Transmit':
            self.broadcast(name, 'Hello', {}, ret)
    def message_handler(self, to, sender, type, body, ret):
        pass
            
                          
if __name__ == '__main__':
    sh = Shim()
    nodes = ['S%d' % i for i in range(50)]
    for node in nodes:
        sh.add_node(BigServer, node, cfg={'cluster': nodes})
    sh.run()
