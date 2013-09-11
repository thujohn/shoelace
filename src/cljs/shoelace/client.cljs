(ns shoelace.client
  (:require
   [clojure.string :refer [join]]
   [hiccups.runtime :as hrt]
   [dommy.core :as dom]
   [dommy.utils :refer [dissoc-in]]
   [cljs.core.async :refer [>! <! chan sliding-buffer]]
   [bigsky.aui.util :refer [event-chan]]
   [bigsky.aui.draggable :refer [draggable]])
  (:require-macros
   [cljs.core.async.macros :refer [go]]
   [dommy.macros :refer [node sel1]]))

(defn spy [x]
  (js/console.log (str x))
  x)

(def col-offset-pos 0)

(def col-width-pos 1)

(def col-height 150)

(def col-width 60)

(def col-margin-width 15)

(def snap-threshold 20)

(def grid-cols 12)

(def body js/document.body)

(def id (atom 1))

(defn new-id! [prefix]
  (swap! id inc)
  (keyword (str prefix "-" @id)))

(def settings (atom {:media-mode :sm
                     :include-container true
                     :active-row :none
                     :output-mode :html}))

(def layout (atom []))

(defn get-by-key
  [col attr val]
  (first (filter (fn [i] (= (attr i) val)) col)))

(defn get-by-id
  [col id]
  (get-by-key col :id id))

(defn get-row
  [row-id]
  (get-by-id @layout row-id))

(defn get-col
  [row-id col-id]
  (get-by-id (:cols (get-row row-id)) col-id))

(def sizes [:xs :sm :md :lg])

(def sizes-index {:xs 0 :sm 1 :md 2 :lg 3})

(defn sizes-up-to
  [max]
  (reverse (subvec sizes 0 (inc (sizes-index max)))))

(def sizes-up-to-memo (memoize sizes-up-to))

(defn col-for-media
  [col media]
  (first (keep #(% col) (sizes-up-to-memo media))))

(defn cols-for-media
  [row media]
  (map #(col-for-media % media) (:cols row)))

(defn total-cols-used
  [row media]
  (apply + (flatten (cols-for-media row media))))

(defn get-el [id]
  (sel1 (keyword (str "#" (name id)))))

(defn calc-col-unit []
  (+ col-margin-width col-width))

(defn get-class-el
  [col-id size type]
  (sel1 (str "#" (name col-id) " ." (name size) "-" (name type))))

(defn add-col!
  [e cols-el new-col-el row-id]
  (let [col-id (new-id! 'col)
        col-el (node [:.col {:id (name col-id)}])
        grow-row-el (sel1 (str "#" (name row-id) " .grow-row"))
        offset-el (node [:.offset])
        remove-el (node [:.remove [:i.icon-remove]])
        width-el (node [:.width])
        nested-el (node [:.nested [:i.icon-th]])
        classes-el (node [:.classes
                          [:.xs-width] [:.xs-offset]
                          [:.sm-width] [:.sm-offset]
                          [:.md-width] [:.md-offset]
                          [:.lg-width] [:.lg-offset]])
        name-el (node [:input.col-name {:placeholder "col"}])
        els {:width width-el
             :offset offset-el}
        type-pos {:offset 0
                  :width 1}
        offset-handle-el (node [:.offset-handle])
        row (get-row row-id)
        total-cols (fn [] (total-cols-used (get-row row-id) (@settings :media-mode)))
        check-to-hide-new-col (fn [] (if (= grid-cols (total-cols))
                                      (dom/add-class! new-col-el :hidden)
                                      (dom/remove-class! new-col-el :hidden)))
        check-to-allow-grow (fn [] (if (= grid-cols (total-cols))
                                   (dom/add-class! grow-row-el :active)
                                   (dom/remove-class! grow-row-el :active)))
        handle-remove (fn [e]
                        (.stopPropagation e)
                        (let [row (get-row row-id)
                              path [(:pos row) :cols]]
                          (swap! layout assoc-in path
                                 (into []
                                       (map-indexed (fn [i c] (assoc c :pos i))
                                                    (filter (fn [c] (not (= (:id c) col-id)))
                                                            (get-in @layout path)))))
                          (dom/remove! col-el)
                          (check-to-hide-new-col)
                          (check-to-allow-grow)
                          (when (zero? (count (get-in @layout path)))
                            (dom/add-class! new-col-el :no-cols))))
        handle-drag (fn [type e]
          (.stopPropagation e)
          (let [start-x (aget e "x")
                start-w (dom/px (els type) "width")
                media (@settings :media-mode)
                col-unit (calc-col-unit)
                row (get-row row-id)
                col (get-col row-id col-id)
                cur-cols-used (col-for-media col media)
                max-cols (- (* grid-cols (media (:height row))) (total-cols-used row media))
                max-width (- (* (+ (cur-cols-used (type-pos type)) max-cols) col-unit) col-margin-width)
                snap! (fn []
                        (let [w (+ (if (= type :offset) col-margin-width 0)
                                   (dom/px (els type) "width"))
                              c (quot w col-unit)
                              r (mod w col-unit)
                              new-width (if (= type :offset)
                                          (max c 0)
                                          ((if (> r snap-threshold) + max) c 1))]
                          (swap! layout assoc-in
                                 [(:pos row) :cols (:pos col) media (type-pos type)]
                                 new-width)
                          (dom/set-text! (get-class-el col-id media type)
                                         (if (and (= type :offset) (zero? new-width))
                                           ""
                                           (str (name media) "-"
                                                (when (= type :offset) "offset-")
                                                new-width)))
                          (dom/add-class! (els type) :easing)
                          (dom/set-px! (els type)
                                       :width
                                       (- (* new-width col-unit)
                                          (if (= type :width) col-margin-width 0)))))
                valid-step (fn [width]
                             (let [c (quot width col-unit)]
                               (or
                                (< c (cur-cols-used (type-pos type)))
                                (and (= max-cols 0)
                                     (< c (cur-cols-used (type-pos type))))
                                (and (or (= type :offset) (> c 0))
                                     (< (+ c (cur-cols-used
                                              (type-pos (if (= type :offset)
                                                          :width
                                                          :offset))))
                                        grid-cols)
                                     (< c (+ max-cols (cur-cols-used (type-pos type))))))))
                move-handler (fn [e]
                               (let [dx (- (aget e "x") start-x)
                                     sdx (+ start-w dx)
                                     nw (if (> sdx  max-width) max-width sdx)]
                                 (when (valid-step nw)
                                   (dom/set-px! (els type) :width nw))))
                stop-handler (fn [e]
                               (dom/unlisten! js/document :mousemove move-handler)
                               (snap!)
                               (check-to-allow-grow)
                               (js/setTimeout
                                #(do (check-to-hide-new-col)
                                     (dom/remove-class! col-el :dragging))
                                300))]
            (dom/add-class! col-el :dragging)
            (dom/remove-class! (els type) :easing)
            (dom/add-class! new-col-el :hidden)
            (dom/listen! js/document :mousemove move-handler)
            (dom/listen-once! js/document :mouseup stop-handler)))]

    (swap! layout update-in [(:pos row) :cols] conj
           {:id col-id
            :pos (count (:cols row))
            (@settings :media-mode) [0 1]})
    (dom/append! width-el nested-el name-el classes-el offset-handle-el)
    (dom/append! col-el offset-el width-el remove-el)
    (dom/append! cols-el col-el)
    (check-to-hide-new-col)
    (check-to-allow-grow)
    (dom/insert-before! col-el new-col-el)
    (dom/remove-class! new-col-el :no-cols)
    (dom/listen! remove-el :mousedown #(handle-remove %))
    (dom/listen! offset-handle-el :mousedown #(handle-drag :offset %))
    (dom/listen! offset-el :mousedown #(handle-drag :offset %))
    (dom/listen! width-el :mousedown #(handle-drag :width %))
    (handle-drag :width e)))

(defn add-row! []
  (this-as new-row-el
           (let [row-id (new-id! "row")
                 row-el (node [:.sl-row {:id (name row-id)}])
                 cols-el (node [:.cols])
                 name-el (node [:input.row-name {:placeholder "Name Row"}])
                 tools-el (node [:.tools])
                 dupe-row-el (node [:span.dupe-row [:i.icon-double-angle-down]])
                 grow-row-el (node [:span.grow-row [:i.icon-level-down]])
                 remv-row-el (node [:span.remv-row [:i.icon-remove]])
                 new-col-el (node [:.new-col.no-cols])
                 clear-el (node [:.clear])]
             (swap! layout conj {:id row-id :pos (count @layout) :cols [] :height {(:media-mode @settings) 1}})
             (dom/append! cols-el new-col-el)
             (dom/append! tools-el dupe-row-el grow-row-el remv-row-el)
             (dom/append! row-el cols-el name-el tools-el clear-el)
             (dom/insert-before! row-el new-row-el)
             (dom/listen! new-col-el :mousedown (fn [e] (add-col! e cols-el new-col-el row-id)))
             (dom/listen! grow-row-el :mousedown (fn [e]
                                                   (let [row (get-row row-id)
                                                         media-mode (:media-mode @settings)]
                                                     (swap! layout assoc-in [(:pos row) :height media-mode]
                                                            (inc (media-mode (:height row))))
                                                     (dom/remove-class! new-col-el :hidden)
                                                     (dom/set-px! row-el :height (+ 160 (dom/px row-el :height)))))))))

(defn layout->html
  [rows]
  (letfn [(size-classes [c]
            (apply str
             (flatten
              (map (fn [s]
                     (if (s c)
                       (let [[offset width] (s c)]
                         [(when (> offset 0)
                            (str ".col-" (name s) "-offset-" offset))
                          (str ".col-" (name s) "-" width)])))
                   sizes))))]
    (map
     (fn [r]
       (conj [:div.row]
             (map (fn [c]
                    [(keyword (str "div" (size-classes c)))])
                  (:cols r))))
     rows)))

(defn draw-workspace []
  (let [workspace (sel1 :.workspace)
        output (sel1 :pre.output)
        container (node [:.container])
        rows (node [:.rows])
        columns (node [:.columns])
        new-row (node [:.sl-row.new-row])
        media-mode (:media-mode @settings)]
    (dom/add-class! container media-mode)

    (doseq [i (range grid-cols)]
      (let [col (node [:.col])]
        (dom/append! columns col)))

    (doseq [size sizes]
      (let [media (sel1 (str ".preview." (name size)))]
        (when (= size media-mode) (dom/add-class! media :active))
        (dom/listen! media :mouseup #(swap! settings assoc :media-mode size))))

    (add-watch settings :update-media
               (fn [k r os ns]
                 (when (not (= (:media-mode os) (:media-mode ns)))
                   (dom/remove-class! container (:media-mode os))
                   (dom/remove-class! (sel1 :.preview.active) :active)
                   (dom/add-class! (sel1 (str  ".preview." (name (:media-mode ns)))) :active)
                   (dom/add-class! container (:media-mode ns)))))

    (add-watch layout :update-output
               (fn [k r os ns]
                 (dom/remove-class! output :prettyprinted)
                 (dom/set-text! output
                  (condp = (:output-mode @settings)
                    :html (js/html_beautify
                           (hrt/render-html
                            (conj [:div.container] (layout->html ns))))

                    :edn (str (mapv (fn [r] (mapv (fn [c] (dissoc c :id :pos)) (:cols r))) ns))))
                 (js/PR.prettyPrint)))


    (dom/listen! new-row :click add-row!)
    (dom/append! container columns rows)
    (dom/append! rows new-row)
    (dom/append! workspace container)))

(draw-workspace)
