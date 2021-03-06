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
;;   DemandSignal
;;      SimulationPolicy
;;        ResponseType
;;           [ACInventory RCInventory]
;;              Period/Response]
(defn path-key [{:keys [ACInventory RCInventory
                        SRC SimulationPolicy DemandSignal AnalysisType
                        ResponseType]}]
  [SRC
   DemandSignal
   SimulationPolicy ;; #_[DemandSignal SimulationPolicy  AnalysisType] ;;altered to pull out DS
   ResponseType      ;;fill/surplus  - currently limited to fill..
   [ACInventory RCInventory]])

;;Defining the structure of our db.  Note: it'd be better if we can derive this
;;structurally, rather than presume what our structure looks like.
(def path-keys [:SRC :DemandSignal :SimulationPolicy :ResponseType :ACRC])
(def path-labels (mapv name path-keys))

;;derive a map-based view of a path key, based on the ordered set of labels
;;in our taxonomy.
(defn path->map [p]
  (into {} 
        (map vector path-keys
             p)))  

#_(defn distinct-fields [xs]
    (reduce (fn [acc r]
              (reduce-kv (fn [acc k v]
                           (assoc acc k
                             (conj (get acc k #{}) v)))
                acc r))
      {} xs))

(defn distinct-fields [xs]
  (let [fields (atom {})
        get-field!  (fn [k]
                      (if-let [res (get fields k)]
                        res
                        (let [flds (atom (transient #{}))]
                          (swap! fields assoc k flds)
                          flds)))]
    (doseq [r xs]
      (reduce-kv (fn [_ k v]
                   (let [vs (get-field! k)
                         _  (swap! vs conj! v)]
                     nil)) nil r))
    (into {} (for [[fld vs] @fields]
               [fld (persistent! @vs)]))))
                   
    
    
  
;;Builds up a nested database
(defn tad-db [xs & {:keys [keyf]
                    :or   {keyf  (juxt :ACInventory
                                       :RCInventory)}}]
  (let [field-domains (distinct-fields xs)]
    (reduce (fn [acc  r]
              (let [k  (path-key r)
                    rs (get-in acc k [])]
                (assoc-in acc k (conj rs (select-keys  r [:Period :Response])))))
            (with-meta {} {:domains field-domains})
            xs)))

;;io 
(defn txt->tad-db [txt]
  (-> (clojure.string/split-lines txt)
      (tbl/lines->records figdemo.tadmudi/tadschema)
      (figdemo.tadmudi/tad-db)))

;;we can query the db to find samples.
(defn find-sample [db [src [pol demand atype] rtype [ac rc]]]
  (for [[p xs]  (->> [src [pol demand atype] rtype [ac rc]]
                     (get-in db)
                     (group-by :Period))
        r xs]
    [p (:Response r)]))

;;what if we can't find a sample?
;;we can use sample-neighbors to lookup the map of samples
;;that we can interpolate off of.

;:SRC :DemandSignal :SimulationPolicy :ResponseType :ACRC
(defn sample-neighbors [db [src demand pol rtype [ac rc]]]
  (get-in db [src demand pol rtype]))

;;Given our neighboring samples...
;;we can get the xy-pairs from them...
;;we can probably cache the results too...specifically when we compute
;;the xy pairs.  All of the surface definitions (i.e. the meshes)
;;are grouped by xy (hence the reason for our tree).
(defn nearest-samples
  "Given a database of points, where k provides a vector of keys into 
   a map, db, where the leaves contain sequences (typically vectors) of 
   records, surfaces, and where the last element of k is a point [x y], abstractly 
   'projects' [x y] onto each s within surfaces, returning a sequence of 
   [surface-name [x y z]] values.  z is either computed directly, if present,
   or interpolated from a plane derived from a nearest-neighbors query based 
   on [x y].  Surface records are maps with conforming to {:keys [Response]}."
  [db k]
  (let [p         (last k)
        [x y]     p
        samples   (sample-neighbors db k)
        ;;this gives us 3 [x y] points to triangulate
        ;;we need [x y z] though.  In this case, there
        ;;are multiple responses, so multiple z sets.
        tri       (proj/nearest p (keys samples))
        zs        (for [k tri
                        r (get samples k)]
                    (let [[x y] k]
                      [(:Period r) [x y (:Response r)]]))
        planes    (reduce (fn [acc [period coords]]
                            (assoc acc period
                                   (conj (get acc period [])
                                         coords))) {} zs)]
        ;;we can project our point onto each plane now.
        
    (-> (for [[period plane] planes]
          [period (proj/onto-plane p (proj/->plane-vec plane))]))))

;;we could replace this with core.logic
;;relations...

;;Note:  I'm operating off the assumption
;;that like-samples exist between cases...

;;Construct a sample by:
;;-Select a Demand  
;;  -Select an SRC
;;    -Select a Supply [ac rc]

;;one common operation will be...
;;find left sample,
;;find right sample?
;;  Do we provide compatible samples?
(defn compatible-samples [db l & {:keys [same-src?]}]
  (let [[src case res & more] l]
    (for [[other-src cases] db
          [othercase results] cases
          [restype qs] results 
          [q xs] qs
          :when (and (= case othercase)
                     (= restype  res)
                     (if same-src? (= src other-src)
                         true))]
      [[other-src case restype q] xs])))

;;Now that we have interactive interpolation...
;;Can we yield surfaces (trends) that can
;;be consumed by other processes?



;;we have a couple of operations that we'd like to
;;perform:
;;browse sample(s)...
;;A sample points to a collection of records.
;;The records are keyed by period and response.

;;given a set of compatible-samples...
;;how can we interpolate?
   
(comment ;testing
  (def res      (io/file->lines (io/current-file)))
  (def db       (txt->tad-db @res))


  (def db (:db @figdemo.core/app-state))
  ;;this gives us a renderable widget ala util/render! 
  (def the-path (util/db->path db :id "the-tree"))
  ;;given the-path, we can
  (util/render! the-path "the-tree")

  ;;Note....there's nothing stopping us from creating
  ;;a like-view, where we have a tablified version of the
  ;;tree, and instead of selecting paths into the tree,
  ;;we just filter based off the table.

  
  ;;now we have access to an interactive path-selector
  ;;when we select leaves in the tree, the value for
  ;;(:path the-path) is updated automagically.
  ;;One thing we're "not" doing is rendering
  ;;the same database into two different views...
  ;;In other words, you have to build to complete
  ;;tree controls that act as "cursors" into the
  ;;selected path of the tree.
  ;;Note: we can create multiple tree controls,
  ;;and subscribe them to path selection...
  ;;Actually, we'll want multiple discrete paths,
  ;;so it's okay to have different widgets...

  (defn make-sample-data []
    (for [src ["770200R00" 
               "Bilbo"
               "Garth"
               "Jabberwocky"]
          policy ["MaxUtilization"
                  "Rotational"]
          demand ["SteadyState"
                  "SS+Surge"]
          atype ["Dynamic"]
          ac-inv (range 10 20 3)
          rc-inv (range 4  18 4)
          r-type ["Fill" "Surplus"]
          p ["PreSurge" "Surge" "PostSurge"]]
      {:SRC src :ACInventory ac-inv :RCInventory rc-inv :SimulationPolicy policy
       :DemandSignal demand :AnalysisType atype :ResponseType r-type :Period p
       :Response (case r-type
                   "Fill"  (rand)
                   (rand-int 10))})))
    
        


(defn td [])
