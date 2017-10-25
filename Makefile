deploy:
	$(eval TMPDIR = $(shell mktemp -d))
	cp -r dviz $(TMPDIR)
	$(MAKE) -C $(TMPDIR)/dviz deploy
	rsync -r $(TMPDIR)/dviz/target/deploy/ $(shell ~/uwplse/getdir)

.PHONY: deploy
