
# modify to Java 8 JDK path if necessary
ifneq ($(shell java -version 2>&1 | grep -q 1.8; printf $$?),0)
	export JAVA_HOME = /Library/Java/JavaVirtualMachines/jdk1.8.0_172.jdk/Contents/Home
	export PATH := $(JAVA_HOME)/bin:$(PATH)
endif

.PHONY: deploy
deploy:
	npm install
	npm run build
	lein uberjar

.PHONY: clean
clean:
	lein clean
