# lekstuga

A poor man's actor library for Clojure and ClojureScript.

## Usage

Run a REPL and connect to it in Emacs (`M-x cider-connect-clj` or `C-c M-c`):

    $ clojure -M:env/test:env/dev:repl/cider

Invoke a library API function from the command-line:

    $ clojure -X eploko.lekstuga/foo :a 1 :b '"two"'
    {:a 1, :b "two"} "Hello, World!"

Run the project's tests (they'll fail until you edit them):

    $ clojure -M:test:runner

Build a deployable jar of this library:

    $ clojure -X:jar

Install it locally:

    $ clojure -M:install

Deploy it to Clojars -- needs `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` environment variables:

    $ clojure -M:deploy

## License

Copyright © 2021 Andrey Subbotin

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
