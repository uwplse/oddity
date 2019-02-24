default: pinger ponger

pinger: pinger.c
	gcc -Wall pinger.c -o pinger

ponger: ponger.c
	gcc -Wall ponger.c -o ponger

run: pinger ponger parent
	../target/debug/majortom ping.toml

parent:
	cd ..; cargo build

clean:
	rm pinger ponger

.PHONY: clean default run parent
