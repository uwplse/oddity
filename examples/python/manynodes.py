"""Generals example
"""

import threading
from shim import Node, Shim

class CoolNode(Node):
    def start_handler(self, name, ret):
        ret.set_timeout('Start', {})
        

    def message_handler(self, to, sender, type, body, ret):
        pass

    def timeout_handler(self, name, type, body, ret):
        pass

if __name__ == '__main__':
    sh = Shim()
    for i in range(20):
        sh.add_node(CoolNode, 'Node' + str(i))
    sh.run()
