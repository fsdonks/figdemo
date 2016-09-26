(ns figdemo.tadmudi
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :as async]
            [goog.dom :as dom]
            [goog.events :as events]
            [figdemo.util :as util]
            [figdemo.io :as io]
            [figdemo.controls :as controls]
            [figdemo.gantt :as gantt]
            [figdemo.spork.util.table :as tbl]
            [figdemo.projector :as proj]))

;;the table schema we're working with is...
;;currently
(def tadschema
  {:SRC          :text 
   :ACInventory  :long
   :RCInventory  :long
   :SimulationPolicy :text 
   :DemandSignal :text 
   :AnalysisType :text 
   :ResponseType :text 
   :Period       :text
   :Response     :double})

;;Go grab our records for the database.
;;The strategy here is to build a computational
;;pipeline that we can operate on.
(defn tad-records [xs]
  (tbl/lines->records xs tadschema))

;;once we have the records, we can build a
;;database from them...
;;Let's go ahead and create a supply key.
;;Since most of our queries will be
;;based on the supply/demand for nearest neighbor
;;resolution, we can cache our dataset into a
;;bunch of groups, keyed off [supply demand].
;;So, we'll get...
;; {[ACInventory RCInventory]
;;    {:SRC
;;     {[:SimulationPolicy :DemandSignal  :AnalysisType]
;;      {:ResponseType {:Period       
;;                      :Response}}}}}

;;our taxonomy is
;;[SRC
;; [SimulationPolicy DemandSignal AnalysisType]
;; ResponseType
;; [ACInventory RCInventory]
;; Period
;; Response]
(defn path-key [{:keys [ACInventory RCInventory
                        SRC SimulationPolicy DemandSignal AnalysisType
                        ResponseType]}]
  [SRC
   [SimulationPolicy DemandSignal AnalysisType]
   ResponseType
   [ACInventory RCInventory]])
  
(defn distinct-fields [xs]
  (reduce (fn [acc r]
            (reduce-kv (fn [acc k v]
                         (assoc acc k
                                (conj (get acc k #{}) v)))
                       acc r))
          {} xs))

;;Builds up a nested database
(defn tad-db [xs & {:keys [keyf]
                    :or {keyf  (juxt :ACInventory
                                     :RCInventory)}}]
  (let [field-domains (distinct-fields xs)]
    (reduce (fn [acc  r]
              (let [k  (path-key r)
                    rs (get-in acc k [])]
                (assoc-in acc k (conj rs (select-keys  r [:Period :Response])))))
            (with-meta {} {:domains (distinct-fields xs)})
            xs)))

;;we can query the db to find samples.
(defn find-sample [db [src [pol demand atype] rtype [ac rc]]]
  (for [[p r]  (->> [src [pol demand atype] rtype [ac rc]]
                     (get-in db)
                     (group-by :Period))]
    [p (:Response r)]))

;;one common operation will be...
;;find left sample,
;;find right sample?
;;  Do we provide compatible samples?
(defn compatible-samples [db l]
  (let [[src case res & more] l]
    (for [[src cases] db
          [othercase results] cases
          [restype qs] results 
          [q xs] qs
          :when (and (= case othercase)
                     (= restype  res))]
      [[src case restype q] xs])))
        
        
   
(comment ;testing
  (def res  (io/file->lines (io/current-file)))
  (def recs (tbl/lines->records (clojure.string/split-lines @res) {}))
  
)

(defn td [])
