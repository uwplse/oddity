import threading
from stateshim import Node, Shim
from raft import RaftServer

if __name__ == '__main__':
    sh = Shim()
    cfg = ['S1', 'S2', 'S3']
    for node in cfg:
        sh.add_node(RaftServer, node, cfg={'cluster': cfg})
    sh.run()
