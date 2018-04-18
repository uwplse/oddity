import threading
from shim import Node, Shim

class RaftClient(Node):
    def start_handler(self, name, ret):
        print self.config()
        for cmd in self.config()['cmds']:
            ret.set_timeout('Command', cmd)
        ret.set_timeout('Retransmit', {})
        ret.set('n', 0)
        ret.set('inflight', None)

    def message_handler(self, to, sender, type, body, ret):
        inflight = ret.get('inflight')
        n = ret.get('n')
        if inflight and body['n'] == n:
            ret.set('n', n+1)
            ret.set('inflight', None)

    def send_to_cluster(self, type, body, ret):
        for node in self.config()['cluster']:
            ret.send(node, type, body)
            
    def timeout_handler(self, name, type, body, ret):
        if type == 'Retransmit':
            inflight = ret.get('inflight')
            if inflight:
                self.send_to_cluster(inflight['type'], inflight['body'], ret)
        elif type == 'Command':
            inflight = ret.get('inflight')
            if not inflight:
                self.send_to_cluster(body['type'], body['body'], ret)
                ret.set('inflight', body)

class RaftServer(Node):
    def start_handler(self, name, ret):
        ret.set('cluster', self.config()['cluster'])
        ret.set('state', 'Follower')
        ret.set('log', [])
        ret.set('commit_index', -1)
        ret.set('term', -1)
        ret.set('match_index', {})
        ret.set('next_index', {})
        ret.set('votes', [])
        ret.set('voted_for', None)

        ret.set_timeout('Election', {})

    def send(self, to, type, body, ret):
        body['term'] = ret.get('term')
        ret.send(to, type, body)
        
    def broadcast(self, name, type, body, ret):
        for s in ret.get('cluster'):
            if s != name:
                self.send(s, type, body, ret)

    def max_index(self, ret):
        log = ret.get('log')
        return len(log) - 1

    def max_term(self, ret):
        log = ret.get('log')
        if log:
            return log[-1]['term']
        return -1

    def replicate_log(self, name, ret, nodes=None):
        if not nodes:
            nodes = [node for node in ret.get('cluster') if node != name]
        for node in nodes:
            next_index = ret.get('next_index').get(node, self.max_index(ret))
            log = ret.get('log')
            entries = log[next_index:]
            prev_index = next_index-1
            prev_term = log[prev_index]['term'] if prev_index >= 0 else -1
            self.send(node, 'AppendEntries',
                      {'entries': entries,
                       'prev_index': prev_index,
                       'prev_term': prev_term,
                       'commit_index': ret.get('commit_index')}, ret)
            
            

    def timeout_handler(self, name, type, body, ret):
        term = ret.get('term')
        state = ret.get('state')
        if type == 'Election':
            ret.set('state', 'Candidate')
            ret.set('term', term + 1)
            ret.set('votes', [name])
            ret.set('voted_for', name)

            self.broadcast(name,
                           'RequestVote',
                           {'max_index': self.max_index(ret),
                            'max_term': self.max_term(ret)},
                           ret)

        elif type == 'Heartbeat':
            if term != self.max_term(ret):
                # We're leader and haven't yet committed an entry in our term
                # Let's commit a dummy entry
                log = ret.get('log')
                entry = {'term': term, 'type': 'dummy'}
                log.append(entry)
                ret.set('log', log)
            self.replicate_log(name, ret)

    def apply_entry(self, entry, ret):
        print 'Applying entry'

    def message_handler(self, to, sender, type, body, ret):
        term = ret.get('term')
        state = ret.get('state')
        if type == 'RequestVote':
            max_term = self.max_term(ret)
            max_index = self.max_index(ret)
            if body['term'] > term:
                ret.set('voted_for', None)
                ret.set('state', 'Follower')
                term = ret.set('term', body['term'])
            if (term <= body['term'] and
                (max_term < body['max_term'] or
                 (max_term == body['max_term'] and max_index <= body['max_index'])) and
                (not ret.get('voted_for') or ret.get('voted_for') == sender)):
                ret.set('voted_for', sender)
                self.send(sender, 'Vote', {}, ret)
        elif type == 'Vote':
            if state == 'Candidate' and body['term'] == term:
                votes = ret.get('votes')
                if sender not in votes:
                    votes.append(sender)
                    ret.set('votes', votes)
                cluster = ret.get('cluster')
                if len(votes) > len(cluster) / 2:
                    ret.set('state', 'Leader')
                    ret.clear_timeout('Election', {})
                    ret.set_timeout('Heartbeat', {})
                    ret.set('match_index', {to: self.max_index(ret)})
                    ret.set('next_index', {})
        elif type == 'AppendEntries':
            if body['term'] < term:
                return
            if body['term'] > term:
                ret.set('voted_for', None)
                ret.set('state', 'Follower')
                term = ret.set('term', body['term'])
            log = ret.get('log')
            if (body['prev_index'] <= self.max_index(ret) and
                (body['prev_index'] == -1 or
                 body['prev_term'] == log[body['prev_index']]['term'])):
                log = log[:body['prev_index']+1]
                log = log + body['entries']
                ret.set('log', log)
                commit_index = ret.get('commit_index')
                if body['commit_index'] > commit_index:
                    for i in range(commit_index+1, body['commit_index']+1):
                        self.apply_entry(log[i], ret)
                    ret.set('commit_index', body['commit_index'])
                self.send(sender, 'AppendEntriesReply',
                          {'ok': True,
                           'max_index': self.max_index(ret)},
                          ret)
                return
            else:
                self.send(sender, 'AppendEntriesReply',
                          {'ok': False,
                           'next_index': body['prev_index']}, ret)
        elif type == 'AppendEntriesReply':
            if body['term'] != term:
                return
            if body['ok']:
                ret.set(['match_index', sender], body['max_index'])
                match_index = ret.get('match_index')
                match_index[to] = self.max_index(ret)
                log = ret.get('log')
                for i in range(ret.get('commit_index')+1, body['max_index']+1):
                    if sum(match_index.get(node, -1) >= i for node in ret.get('cluster')) > len(ret.get('cluster')) / 2:
                        self.apply_entry(log[i], ret)
                        ret.set('commit_index', i)
            else:
                ret.set('next_index', sender, body['next_index'])
                self.replicate_log(to, ret, nodes=[sender])
                          
if __name__ == '__main__':
    sh = Shim()
    cluster = ['S1', 'S2', 'S3', 'S4', 'S5']
    cfg1 = ['S1', 'S2', 'S3', 'S4']
    cfg2 = ['S1', 'S2', 'S3', 'S4', 'S5']
    cfg3 = ['S2', 'S3', 'S4']
    sh.add_node(RaftClient, 'client',
                cfg={'cluster': cluster,
                     'cmds': [{'type': 'Reconfig', 'body': {'cluster': cfg2}},
                              {'type': 'Reconfig', 'body': {'cluster': cfg3}}]})
    for node in cfg1:
        sh.add_node(RaftServer, node, cfg={'cluster': cfg1})
    sh.add_node(RaftServer, 'S5', cfg={'cluster': {}})
    sh.run()
