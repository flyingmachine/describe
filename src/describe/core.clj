(ns describe.core
  (:require [loom.graph :as lg]
            [loom.alg :as la]
            [loom.attr :as lat]
            [loom.derived :as ld]
            [clojure.spec.alpha :as s]))

(s/def ::pred ifn?)
(s/def ::args seqable?)
(s/def ::dscr any?)

(s/def ::describer (s/keys :req-un [::pred ::args ::dscr]))
(s/def ::describer-coll (s/coll-of ::describer))
(s/def ::describer-map (s/map-of ::describer ::describer-coll))

(s/def ::describer-node-def (s/or :describer ::describer
                                  :describer-coll ::describer-coll
                                  :describer-map ::describer-map))
(s/def ::describer-nodes (s/coll-of ::describer-node))

(defn context
  [cfn]
  {:pre [(ifn? cfn)]}
  (let [context-fn (if (fn? cfn) cfn #(cfn %))]
    (with-meta context-fn {::ctx true})))

(defn graph
  "Allows slightly more compact syntax for defining graphs by treating a
  vector of [:a :b :c] as pairs [:a :b] [:b :c] and by treating
  describer maps as nodes instead of attempting to treat them as maps
  describing nodes and edges"
  [node-defs]
  ;; TODO update to provide more informative group by: group by describer / describer-graph
  (let [grouped (group-by #(= :describer (first (s/conform ::describer-node-def %))) node-defs)]
    (apply lg/add-nodes
           (apply lg/digraph (reduce (fn [all-nodes node-data]
                                       (if (or (map? node-data) (not (seqable? node-data)))
                                         (conj all-nodes node-data)
                                         (into all-nodes (partition 2 1 node-data))))
                                     []
                                     (get grouped false)))
           (get grouped true))))

(defn resolve-args
  [ctx args]
  (reduce (fn [resolved arg]
            (conj resolved (cond (::ctx (meta arg)) (arg ctx)
                                 (ifn? arg)         (arg (::subject ctx))
                                 :else              arg)))
          []
          args))

(defn describer-applies?
  [ctx describer-graph describer]
  (let [{:keys [pred args]} describer]
    (apply pred (resolve-args ctx args))))

(defn convert-describers
  [describers]
  (if (lg/graph? describers)
    (if (lg/directed? describers)
      describers
      (throw (AssertionError. "when describers is a graph, it must be a digraph")))
    (graph describers)))

(defn add-description
  [descriptions ctx describer]
  (let [{:keys [dscr args as]} describer]
    (conj descriptions [(or as (first args)) dscr])))

(defn remove-describer-subgraph
  [describer-graph describer]
  (->> (ld/subgraph-reachable-from describer-graph describer)
       lg/nodes
       (apply lg/remove-nodes describer-graph)))

(defn describe
  [x describers & [additional-ctx]]
  (let [ctx (merge additional-ctx {::subject x})]
    (loop [describer-graph (convert-describers describers)
           descriptions    #{}
           remaining       (la/topsort describer-graph)]
      (if (empty? remaining)
        descriptions
        (let [describer               (first remaining)
              applies?                (describer-applies? ctx describer-graph describer)
              updated-describer-graph (cond-> (lat/add-attr describer-graph describer :applied? applies?)
                                        applies? (remove-describer-subgraph describer))]
          (recur updated-describer-graph
                 (if applies?
                   (add-description descriptions ctx describer)
                   descriptions)
                 (->> (rest remaining)
                      (filter (set (lg/nodes updated-describer-graph))))))))))
