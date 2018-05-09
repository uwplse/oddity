import sys
import json

def main():
    # TODO check args

    with open(sys.argv[1], 'r') as f:
        events = json.loads(f.read())

    for e in events:
        print e

main()
