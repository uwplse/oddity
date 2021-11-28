"""Generals example
"""

import threading
from stateshim import Node, Shim

class Client(Node):
    def start_handler(self, name, ret):
        ret.set_timeout('Start', {})
        

    def message_handler(self, to, sender, type, body, ret):
        pass

    def send_to_cluster(self, type, body, ret):
        for node in ret.get('cluster'):
            ret.send(node, type, body)
            
    def timeout_handler(self, name, type, body, ret):
        if type == 'Start':
            ret.clear_timeout('Start', {})
            ret.set_timeout('Send-A', {})
            ret.send('Server', 'CK', {})
            ret.send('Server', 'D', {})
            ret.send('Server', 'B', {'Contents': 'This message has them'})
        else:
            ret.clear_timeout('Send-A', {})
            ret.send('Server', 'A', {})

class Server(Node):
    def start_handler(self, name, ret):
        ret.state['state'] = ''

    def timeout_handler(self, name, type, body, ret):
        pass

    def message_handler(self, to, sender, type, body, ret):
        ret.state['state'] += type
            
                          
if __name__ == '__main__':
    sh = Shim()
    sh.add_node(Client, 'Client')
    sh.add_node(Server, 'Server')
    sh.run()
