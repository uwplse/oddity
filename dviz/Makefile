deploy:
	wget https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein
	chmod +x ./lein
	./lein
	./lein minify-assets
	./lein cljsbuild once deploy
	cp -r resources/public/* target/deploy/
	rsync -r target/deploy/ $(shell ~/uwplse/getdir)

.PHONY: deploy
