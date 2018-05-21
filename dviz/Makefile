
# modify to Java 8 JDK path if necessary
#export JAVA_HOME = /Library/Java/JavaVirtualMachines/jdk1.8.0_172.jdk/Contents/Home
#export PATH := $(JAVA_HOME)/bin:$(PATH)

.PHONY: deploy
deploy:
	npm install
	npm run build
	lein uberjar

.PHONY: clean
clean:
	lein clean
