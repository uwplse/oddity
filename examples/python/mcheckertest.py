"""Generals example
"""

import threading
from shim import Node, Shim

N_NODES = 2
def next_node(name):
    return 'Node' + str((int(name[4:]) + 1) % N_NODES)

class Node(Node):
    def start_handler(self, name, ret):
        ret.set_timeout('Timeout', {})
        if name == 'Node0':
            ret.send(next_node(name), 'Ping', {})
        ret.set('pings', 0)
        ret.set('timeouts', 0)
        
    def message_handler(self, to, sender, type, body, ret):
        ret.set('pings', ret.get('pings') + 1)
        ret.send(next_node(to), 'Ping', {})

            
    def timeout_handler(self, name, type, body, ret):
        ret.set('timeouts', ret.get('timeouts') + 1)
                          
if __name__ == '__main__':
    sh = Shim()
    for n in range(N_NODES):
        sh.add_node(Node, 'Node' + str(n))
    sh.run()
