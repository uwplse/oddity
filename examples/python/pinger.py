"""Generals example
"""

import threading
from shim import Node, Shim

class Server(Node):
    def start_handler(self, name, ret):
        if name == 'A':
            ret.set_timeout('Send-ping', {})

    def timeout_handler(self, name, type, body, ret):
        if name == 'A':
            ret.send('Node', 'HELLO', {})

    def message_handler(self, to, sender, type, body, ret):
        pass
            
                          
if __name__ == '__main__':
    sh = Shim()
    sh.add_node(Server, 'A')
    sh.add_node(Server, 'Node')
    sh.run()
