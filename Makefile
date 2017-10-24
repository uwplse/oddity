deploy:
	cd dviz
	wget https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein
	chmod +x ./lein
	./lein
	./lein minify-assets
	./lein cljsbuild once deploy
	rsync -r resources/public/ $(shell ~/uwplse/getdir)

.PHONY: deploy
