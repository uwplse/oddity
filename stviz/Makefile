default: example.png example-log.png

%.png: %.dot
	dot -Tpng $< > $@

.PRECIOUS: %.dot
%.dot: %.json
	python log2dot.py $< > $@

clean:
	rm -f *.png
	rm -f example-log.dot
