(ns hiposfer.kamal.sqlite
  (:gen-class)
  (:require [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :refer [instrument]]
            [expound.alpha :as expound]
            [hiposfer.kamal.router.io.osm :as osm]
            [hiposfer.kamal.router.util.geometry :as geometry])
  (:import (java.io IOException)
           (java.net URL URLEncoder)))

(def expound-printer (expound/custom-printer {:show-valid-values? false
                                              :theme              :figwheel-theme
                                              :print-specs?       false}))

(alter-var-root #'s/*explain-out* (constantly expound-printer))

(instrument)

(def schema (slurp "resources/sqlite/schema.sql"))

;(def db-uri "jdbc:sqlite::memory:")

(def graph-file "resources/graph.db")
(def graph-uri (str "jdbc:sqlite:" graph-file))

(def outdir "resources/test/")
(defn- osm-filename [area] (str outdir (:area/id area) ".osm"))

(defn- fetch-osm!
  "read OSM data either from a local cache file or from the overpass api"
  [area]
  (if (.exists (io/file (osm-filename area)))
    (do (println "OK - OSM file found")
        (osm-filename area))
    (let [query (str/replace (slurp (io/resource "overpass-api-query.txt"))
                             "Niederrad"
                             (:area/name area))
          url   (str "http://overpass-api.de/api/interpreter?data="
                     (URLEncoder/encode query "UTF-8"))
          conn  (. ^URL (io/as-url url) (openConnection))]
      (println "no OSM cache file found ... fetching")
      (io/copy (. conn (getContent))
               (io/file (osm-filename area)))
      (println "OK - writing OSM cache file" (osm-filename area))
      (osm-filename area))))
;;(fetch-osm! {:area/id "frankfurt" :area/name "Frankfurt am Main"})


(defn -main
  "Script for preprocessing OSM and GTFS files into gzip files each with
  a Datascript EDN representation inside"
  []
  (try (io/delete-file graph-file)
       (println (str graph-file " deleted"))
       (catch IOException e))
  (with-open [conn (jdbc/get-connection graph-uri)
              stream (io/input-stream (fetch-osm! {:area/id   "niederrad"
                                                   :area/name "Niederrad"}))]
    ;; execute each statement separately
    (doseq [statement (str/split schema #";\n")]
      (jdbc/execute! conn [statement]))
    (doseq [tx (apply concat (osm/transaction! stream))
            :when (some? tx)]
      (sql/insert! conn (namespace (ffirst tx)) tx))))
    ;; TODO: execute in a terminal
    ;; .open graph-file
    ;; .dump

;(-main)

#_(with-open [conn (jdbc/get-connection graph-uri)]
    (let [rows      (jdbc/execute! conn ["select * from way_node join node on way_node.node = node.id"])]
      (doseq [path      (partition-by :way_node/way rows)
              [from to] (map vector path (rest path))]
        (let [distance (geometry/haversine [(:node/lon from) (:node/lat from)]
                                           [(:node/lon to) (:node/lat to)])]
          (sql/insert! conn "link"
            {:link/src      (:way_node/node from)
             :link/dst      (:way_node/node to)
             :link/distance distance})))))
