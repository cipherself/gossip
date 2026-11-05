(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'gossip/gossip)
(def version "0.2.0")
(def class-dir "target/classes")
(def jar-file (str "target/gossip-" version ".jar"))
(def pom-file (str class-dir "/META-INF/maven/gossip/gossip/pom.xml"))

(defn jar
  "Build the library JAR and Maven metadata."
  [_]
  (b/delete {:path "target"})
  (b/write-pom
    {:class-dir class-dir
     :lib lib
     :version version
     :basis (b/create-basis {:project "deps.edn" :user nil})
     :src-dirs ["src"]
     :pom-data [[:description "Gossip dissemination of messages over unreliable networks."]
                [:url "https://github.com/cipherself/gossip"]
                [:licenses
                 [:license
                  [:name "MIT License"]
                  [:url "https://opensource.org/licenses/MIT"]]]
                [:scm
                 [:url "https://github.com/cipherself/gossip"]
                 [:connection "scm:git:https://github.com/cipherself/gossip.git"]
                 [:tag version]]]})
  (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
  (b/copy-file {:src "LICENSE" :target (str class-dir "/LICENSE")})
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println "Built" jar-file))

(defn deploy
  "Build and publish to Clojars using CLOJARS_USERNAME and CLOJARS_PASSWORD."
  [_]
  (jar nil)
  (dd/deploy {:installer :remote :artifact jar-file :pom-file pom-file}))
