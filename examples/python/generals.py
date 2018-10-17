"""Generals example
"""

import threading
from shim import Node, Shim

class Client(Node):
    def start_handler(self, name, ret):
        ret.set_timeout('Send-A', {})
        ret.send('Server', 'C', {})
        ret.send('Server', 'D', {})
        ret.send('Server', 'B', {'Contents': 'This message has them'})

    def message_handler(self, to, sender, type, body, ret):
        pass

    def send_to_cluster(self, type, body, ret):
        for node in ret.get('cluster'):
            ret.send(node, type, body)
            
    def timeout_handler(self, name, type, body, ret):
        ret.send('Server', 'A', {})

class Server(Node):
    def start_handler(self, name, ret):
        ret.set('state', '')

    def timeout_handler(self, name, type, body, ret):
        pass

    def message_handler(self, to, sender, type, body, ret):
        state = ret.get('state')
        ret.set('state', state + type)
            
                          
if __name__ == '__main__':
    sh = Shim()
    sh.add_node(Client, 'Client')
    sh.add_node(Server, 'Server')
    sh.run()
