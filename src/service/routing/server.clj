(ns service.routing.server
  (:require [clojure.spec.gen.alpha :as gen]
            [ring.util.http-response :refer [ok]]
            [compojure.api.sweet :refer [context GET api]]
            [service.routing.spec :as spec]
            [service.routing.directions :as dir]
            [service.routing.graph.generators :as g]
            [clojure.string :as str]
            [clojure.edn :as edn]))

(defn- parse-coordinates
  [text]
  (let [pairs (str/split text #";")]
    (for [coords pairs]
      (mapv edn/read-string (str/split coords #",")))))
;(->coordinates "-122.42,37.78;-77.03,38.91")

(defn- parse-radiuses
  [text]
  (map edn/read-string (str/split text #";")))
;(->radiuses "1200.50;100;500;unlimited;100")


(def app
  (api {:swagger {:ui "/"
                  :spec "/swagger.json"
                  :data {:info {:title "Routing API"
                                :description "Routing for hippos"}
                         :tags [{:name "direction", :description "direction similar to mabbox"}]}}}
    (GET "/spec/direction/:coordinates" []
      :coercion :spec
      :summary "direction with clojure.spec"
      :path-params [coordinates :- ::spec/raw-coordinates]
      :query-params [{steps :- boolean? false}
                     {radiuses :- ::spec/raw-radiuses nil}
                     {alternatives :- boolean? false}
                     {language :- string? "en"}]
      :return ::spec/direction
      (ok (let [coordinates (parse-coordinates coordinates)
                radiuses    (some-> radiuses (parse-radiuses))]
            (if (and (not-empty radiuses) (not= (count radiuses) (count coordinates)))
              {:message "The same amount of radiouses and coordinates must be provided"
               :code    "InvalidInput"}
              (dir/direction (g/complete (gen/generate (g/graph 1000)))
                             :coordinates coordinates
                             :steps steps
                             :radiuses radiuses
                             :alternatives alternatives
                             :language language)))))))