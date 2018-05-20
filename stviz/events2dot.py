import sys
import edn_format

def main():
    trace = edn_format.loads(sys.stdin.read())
    print(trace)

##     nodes = set()
##     for e in trace:
##         try:
##             nodes.add(e['to'])
##             nodes.add(e['from'])
##         except:
##             pass
##
##     events = []
##     for e in trace:
##         try:
##             if e['msgtype'] == 'msg':
##                 events.append({ 'typ'  : 'msg'
##                               , 'from' : e['from']
##                               , 'to'   : e['to']
##                               , 'lbl'  : e['type']
##                               })
##             if e['msgtype'] == 'timeout':
##                 events.append({ 'typ'  : 'tmout'
##                               , 'to'   : e['to']
##                               , 'lbl'  : e['type']
##                               })
##         except:
##             pass
##
##     # need a final layer for last message to arrive in
##     nlayers = len(events) + 1
##
##     print('''
## digraph {
##   node  [ shape    = none
##         ; fontname = "helvetica bold"
##         ; fontsize = "18pt"
##         ];
##   edge  [ color    = gray41
##         ; penwidth = 0.75
##         ; fontname = "helvetica bold"
##         ; fontsize = "18pt"
##         ];
## ''')
##
##     for n in nodes:
##         fmt = '%s -> %s_%04d [group = %s, style = invis];'
##         print(fmt % (n, n, 0, n))
##         for i in range(nlayers):
##             if i > 0:
##                 fmt = '%s_%04d -> %s_%04d_mid [dir = none, weight = 1000, minlen = 1.5];'
##                 print(fmt % (n, i - 1, n, i - 1))
##                 fmt = '%s_%04d_mid -> %s_%04d [dir = none, weight = 1000, minlen = 1.5];'
##                 print(fmt % (n, i - 1, n, i))
##             fmt = '%s_%04d [shape = point, width = 0, height = 0];'
##             print(fmt % (n, i))
##             fmt = '%s_%04d_mid [shape = point, width = 0, height = 0];'
##             print(fmt % (n, i))
##         print('')
##     print('')
##
##     for i in range(nlayers):
##         print('{rank = same;')
##         for n in nodes:
##             fmt = '  %s_%04d [group = %s];'
##             print(fmt % (n, i, n))
##         print('}\n')
##     print('')
##
##     print('''
##   edge  [ color    = black
##         ; penwidth = 1.75
##         ];
## ''')
##
##     for i in range(len(events)):
##         e = events[i]
##         if e['typ'] == 'msg':
##             fmt = '%s_%04d -> %s_%04d [xlabel = "%s", ' \
##                 + 'weight = 0, constraint = false];'
##             print(fmt % ( e['from'], i
##                         , e['to'], i + 1
##                         , e['lbl']
##                         ))
##         elif e['typ'] == 'tmout':
##             fmt = '%s_%04d_mid [shape = box; label = "%s"];'
##             print(fmt % ( e['to'], i
##                         , e['lbl']
##                         ))
##         else:
##             raise Exception('invalid event type')
##     print('')
##
##     print('}')

main()
