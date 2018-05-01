(ns hiposfer.kamal.parsers.osm
  (:require [clojure.data.xml :as xml]
            [hiposfer.kamal.network.core :as network]
            [clojure.java.io :as io])
  (:import (org.apache.commons.compress.compressors.bzip2 BZip2CompressorInputStream)))

(defn- bz2-reader
  "Returns a streaming Reader for the given compressed BZip2
  file. Use within (with-open)."
  [filename]
  (-> (io/input-stream filename)
      (BZip2CompressorInputStream.)
      (io/reader)))

;;TODO: include routing attributes for penalties
;; bridge=yes      Also true/1/viaduct
;; tunnel=yes      Also true/1
;; surface=paved   No Speed Penalty. Also cobblestone/asphalt/concrete
;; surface=unpaved Speed Penalty. Also dirt/grass/mud/earth/sand
;; surface=*       Speed Penalty (all other values)
;; access=private

;; name=*   Street Name (Official). Also official_name / int_name / name:en / nat_name
;; name_1=* Street Name (Alternate). Also reg_name / loc_name / old_name
;; ref=*    Route network and number, but information from parent Route Relations has priority,
;;          see below. Also int_ref=* / nat_ref=* / reg_ref=* / loc_ref=* / old_ref=*
(def way-attrs #{"name" "id" "nodes"}) ;:name_1 :ref}})

;; <node id="298884269" lat="54.0901746" lon="12.2482632" user="SvenHRO"
;;      uid="46882" visible="true" version="1" changeset="676636"
;;      timestamp="2008-09-21T21:37:45Z"/>)
(defn- point-entry
  "takes an OSM node and returns a [id Node-instance]"
  [element] ; returns [id node] for later use in int-map
  {:node/id  (Long/parseLong (:id  (:attrs element)))
   :node/location (network/->Location (Double/parseDouble (:lon (:attrs element)))
                                      (Double/parseDouble (:lat (:attrs element))))})

; <way id="26659127" user="Masch" uid="55988" visible="true" version="5" changeset="4142606"
      ;timestamp="2010-03-16T11:47:08Z">
;   <nd ref="292403538"/>
;   <nd ref="298884289"/>
;   ...
;   <nd ref="261728686"/>
;   <tag k="highway" v="unclassified"/>
;   <tag k="name" v="Pastower Straße"/>
;  </way>
(defn- ways-entry
  "parse a OSM xml-way into a [way-id {attrs}] representing the same way"
  [element] ;; returns '(arc1 arc2 ...)
  (let [attrs (into {} (comp (filter #(= :tag (:tag %)))
                             (map    (fn [el] [(:k (:attrs el))
                                               (:v (:attrs el))])))
                       (:content element))
        nodes (into [] (comp (filter #(= :nd (:tag %)))
                             (map (comp :ref :attrs))
                             (map #(Long/parseLong %)))
                       (:content element))]
    (merge attrs
      {"id" (Long/parseLong (:id (:attrs element)))
       "nodes" nodes})))

;; we ignore the oneway tag since we only care about pedestrian routing. See
;; https://wiki.openstreetmap.org/wiki/OSM_tags_for_routing/Telenav
(defn- postprocess
  "return a {id way} pair with all unnecessary attributes removed with the
   exception of ::nodes. Reverse nodes if necessary"
  [way]
  (into {} (map (fn [[k v]] [(keyword "way" k) v])
                (select-keys way way-attrs))))

(defn- strip-points
  "returns way with only the first/last and intersection nodes"
  [intersections way]
  (let [intersections (conj intersections (first (:way/nodes way))
                                          (last  (:way/nodes way)))]
    (assoc way :way/nodes (filter intersections (:way/nodes way)))))

(defn simplify
  "returns the ways sequence including only the first/last and intersection
   nodes i.e. the connected nodes in a graph structure.

  Uses the ::nodes of each way and counts which nodes appear more than once"
  [ways]
  (let [point-count   (frequencies (mapcat :way/nodes ways))
        intersections (into #{} (comp (filter #(>= (second %) 2))
                                      (map first))
                                point-count)]
    (map #(strip-points intersections %) ways)))

(defn- entries
  "returns a [id node], {id way} or nil otherwise"
  [xml-entry]
  (case (:tag xml-entry)
    :node (point-entry xml-entry)
    :way  (postprocess (ways-entry xml-entry))
    nil))

;; There are generally speaking two ways to process an OSM file for routing
; - read all points and ways and transform them in memory
; - read it once, extract the ways and then read it again and extract only the relevant nodes

;; The first option is faster since reading from disk is always very slow
;; The second option uses less memory but takes at least twice as long (assuming reading takes the most time)

;; We use the first option since it provides faster server start time. We assume
;; that preprocessing the files was already performed and that only the useful
;; data is part of the OSM file. See README

;; TODO: deduplicate strings in ways name
(defn datomize
  "read an OSM file and transforms it into a network of {:graph :ways :points},
   such that the graph represent only the connected nodes, the points represent
   the shape of the connection and the ways are the metadata associated with
   the connections"
  [filename] ;; read all elements into memory
  (let [nodes&ways    (with-open [file-rdr (bz2-reader filename)]
                        (into [] (comp (map entries)
                                       (remove nil?))
                              (:content (xml/parse file-rdr))))
        ;; separate ways from nodes
        ways          (simplify (filter :way/id nodes&ways))
        ;; post-processing nodes
        ids           (into #{} (mapcat :way/nodes) ways)
        nodes         (sequence (comp (filter :node/id)
                                      (filter #(contains? ids (:node/id %))))
                                nodes&ways)
        neighbours    (for [way ways]
                        (map (fn [from to]
                               {:node/id         from
                                :node/successors #{[:node/id to]}})
                             (:way/nodes way)
                             (rest (:way/nodes way))))]
    (concat nodes
            (sequence cat neighbours)
            (for [way ways]
              (assoc way :way/nodes
                (for [n (:way/nodes way)]
                  [:node/id n]))))))


;; https://www.wikiwand.com/en/Preferred_walking_speed
(def walking-speed  1.4);; m/s

;; LEARNINGS ----- resources/osm/saarland.osm
;; way count
;; - without filtering for highways 169241
;; - only highways                   72342
;; - only pedestrian highways        61986

;; node count
;; - without filtering for highways 1119289
;; - only highways                   470230
;; - only connecting highways         73614