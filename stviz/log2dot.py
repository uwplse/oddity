import sys
import json

def main():
    events = json.loads(sys.stdin.read())

    nodes = set()
    for e in events:
        try:
            nodes.add(e['from'])
            nodes.add(e['to'])
        except:
            pass

    msgs = []
    for e in events:
        try:
            if e['msgtype'] == 'msg':
                msgs.append({ 'from': e['from']
                            , 'to': e['to']
                            , 'lbl': e['type']
                            })
        except:
            pass

    # need a final layer for last message to arrive in
    nlayers = len(msgs) + 1

    print('''
digraph {
  node  [ shape    = box
        ; fontname = "helvetica bold"
        ; fontsize = "18pt"
        ];
  edge  [ color    = gray41
        ; penwidth = 0.75
        ; fontname = "helvetica bold"
        ; fontsize = "18pt"
        ];
''')

    for n in nodes:
        fmt = '%s -> %s_%04d [group = %s, style = invis];'
        print(fmt % (n, n, 0, n))
        for i in range(nlayers):
            if i > 0:
                fmt = '%s_%04d -> %s_%04d [dir = none, weight = 1000, minlen = 3];'
                print(fmt % (n, i - 1, n, i))
            fmt = '%s_%04d [shape = point, width = 0, height = 0];'
            print(fmt % (n, i))
        print('')
    print('')

    for i in range(nlayers):
        print('{rank = same;')
        for n in nodes:
            fmt = '  %s_%04d [group = %s];'
            print(fmt % (n, i, n))
        print('}\n')
    print('')

    print('''
  edge  [ color    = black
        ; penwidth = 1.75
        ];
''')

    for i in range(len(msgs)):
        fmt = '%s_%04d -> %s_%04d [xlabel = "%s", ' \
            + 'weight = 0, constraint = false];'
        print(fmt % ( msgs[i]['from'], i
                    , msgs[i]['to'], i + 1
                    , msgs[i]['lbl']
                    ))
    print('')

    print('}')

main()
