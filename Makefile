.PHONY: deploy
deploy:
	$(MAKE) -C dviz

.PHONY: clean
clean:
	$(MAKE) -C dviz clean
