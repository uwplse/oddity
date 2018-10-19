# Oddity


[![Build Status](https://api.travis-ci.org/uwplse/oddity.svg?branch=master)](https://travis-ci.org/uwplse/oddity)

Oddity is a graphical interactive debugger for distributed systems.

## Getting Started

Download the [latest version](https://github.com/uwplse/oddity/releases/latest);
you want the `oddity.jar` file. Start it up:

```
java -jar oddity.jar
```

There are some example systems in the `examples` directory of thie
respository. To run a version of the Raft consensus protocol:

```
cd examples/python
python2.7 raft.py
```

Navigate to [http://localhost:3000] and you can start playing with Raft!

## Developing Oddity

Oddity is written in Clojure and Clojurescript. To build Oddity, you'll need
[Leiningen](https://leiningen.org/) and [node.js](https://nodejs.org/). On
MacOS, you can get these with

```
brew install leiningen node
```

You can run Oddity in development in a couple of ways. Either way, you'll need
to first install the Oddity frontend's javascript dependencies:

```
cd oddity
npm install
npm run build
```

Then, install the Clojure dependencies:

```
lein deps
```

### The Leiningen REPL

The easiest way to run Oddity in development is from the Leiningen REPL. You can
start it with

```
lein repl
```

This is a Clojure REPL with Oddity's libraries loaded. You can start Oddity by
typing

```
(go)
```

You can then visit [http://localhost:3000].

### Emacs + CIDER

For developing Oddity, I recommend Emacs with the CIDER Clojure
environment. With CIDER installed, you can open `oddity/project.clj` (or any
other Clojure or Clojurescript source file) and hit, I am not joking here, `C-c
C-x j m` in order to start Clojure and Clojurescript REPLs connected to
Oddity. This setup provides live code reloading and a browser-connected REPL
courtesy of [Figwheel](https://github.com/bhauman/lein-figwheel). It's pretty
cool!

## Testing

You can run the tests with

```
lein test
```
