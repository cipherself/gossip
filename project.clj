(defproject gossip "0.1.0"
  :description "gossip is a library to do gossip dissemination of messages in a decentralized manner over unreliable networks."
  :url "https://github.com/cipherself/gossip"
  :license {:name "MIT public license"
            :url "http://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [udp-wrapper "0.1.1"]
                 [overtone/at-at "1.2.0"]]
  :profiles {:dev {:dependencies [[org.clojure/tools.nrepl "0.2.12"]]
                   :plugins [[jonase/eastwood "0.2.1"]
                             [lein-kibit "0.1.2"]
                             [lein-bikeshed "0.2.0"]]}}
  :aliases {"checkall" ["do" "check," "kibit," "eastwood," "bikeshed"]})
