(ns talk.display
  (:require ["./ably.js" :as ably]
            ["./presenter.js" :as presenter]
            ["./ui.js" :as ui]
            [clojure.string :as str]))

(def persimmon-img "/persimmon.jpeg")


;; >> State

(def default-state {:slide-id nil
                    :votes {}  ;; {question-id -> {:latest-vote {client-id -> vote}, :all-votes [vote]}}
                    :audience-count 0
                    :speaker-messages []
                    :reactions []})

(defn save-state! []
  (js/localStorage.setItem "display-state"
    (js/JSON.stringify (select-keys @state [:votes]))))

(defn load-state []
  (when-let [saved (js/localStorage.getItem "display-state")]
    (js/JSON.parse saved)))

(def state (atom (merge default-state (load-state))))

(defn reset-state! []
  (let [audience-count (:audience-count @state)]
    (js/localStorage.removeItem "display-state")
    (reset! state (assoc default-state :audience-count audience-count))
    ;; Re-fetch presenter state
    (presenter/get-state
      (fn [presenter-state]
        (when presenter-state
          (swap! state assoc :slide-id (:slide-id presenter-state)))))))



;; >> Vote Processing

(defn process-vote [vote]
  (let [client-id (:client-id vote)
        question-id (:question-id vote)]
    (swap! state (fn [s]
                   (-> s
                       (assoc-in [:votes question-id :latest-vote client-id] vote)
                       (update-in [:votes question-id :all-votes] (fnil conj []) vote))))
    (save-state!)))



;; >> Speaker Message Processing

(defn process-speaker-message [msg]
  (if (= (:command msg) "clear")
    (swap! state assoc :speaker-messages [])
    (swap! state update :speaker-messages
           (fn [msgs]
             (->> (cons msg msgs)
                  (take 5)
                  vec)))))



;; >> Reaction Processing

(def MAX-REACTIONS 100)
(def REACTION-LIFETIME 4000) ;; ms

(defn process-reaction [reaction]
  (let [x (+ 10 (rand-int 80))  ;; random x position 10-90%
        new-reaction (assoc reaction :x x :created-at (js/Date.now))]
    (swap! state update :reactions
           (fn [reactions]
             (->> (conj reactions new-reaction)
                  (take-last MAX-REACTIONS)
                  vec)))))

(defn cleanup-old-reactions! []
  (let [now (js/Date.now)
        cutoff (- now REACTION-LIFETIME)]
    (swap! state update :reactions
           (fn [reactions]
             (filterv #(> (:created-at %) cutoff) reactions)))))



;; >> Aggregation

(defn get-votes-for [question-id]
  (vals (get-in @state [:votes question-id :latest-vote])))

(defn scale-stats [question-id]
  (let [votes (get-votes-for question-id)
        values (map :value votes)]
    (when (seq values)
      {:count (count values)
       :average (/ (reduce + values) (count values))
       :distribution (frequencies values)})))

(defn choice-stats [question-id]
  (frequencies (map :value (get-votes-for question-id))))

(defn text-responses [question-id]
  (map :value (get-votes-for question-id)))

(defn response-count [question-id]
  (count (get-votes-for question-id)))

(defn running-averages [question-id]
  "Returns a vector of {:timestamp :average :count} for each vote received,
   where average is computed from the latest vote per client at that point in time"
  (let [all-votes (get-in @state [:votes question-id :all-votes] [])
        sorted-votes (sort-by :timestamp all-votes)]
    (loop [votes sorted-votes
           client-votes {}  ;; {client-id -> value}
           results []]
      (if (empty? votes)
        results
        (let [{:keys [client-id value timestamp]} (first votes)
              new-client-votes (assoc client-votes client-id value)
              values (vals new-client-votes)
              new-avg (/ (reduce + values) (count values))]
          (recur (rest votes)
                 new-client-votes
                 (conj results {:timestamp timestamp
                                :average new-avg
                                :count (count new-client-votes)})))))))



;; >> Floating Reactions Layer

(defn floating-emoji [{:keys [emoji x id created-at]}]
  (let [age-ms (- (js/Date.now) created-at)
        ;; Negative delay "fast forwards" the animation to the right point
        delay-s (/ (- age-ms) 1000)]
    [:div {:key id
           :class "absolute text-5xl pointer-events-none animate-float-up"
           :style {:left (str x "%")
                   :bottom "0%"
                   :transform "translateX(-50%)"
                   :animation-delay (str delay-s "s")}}
     emoji]))

(defn reactions-layer []
  (let [reactions (:reactions @state)]
    [:div {:class "fixed inset-0 overflow-hidden pointer-events-none z-50"}
     [:style "
       @keyframes float-up {
         0% { bottom: 0%; opacity: 1; }
         100% { bottom: 100%; opacity: 0; }
       }
       .animate-float-up {
         animation: float-up 4s linear forwards;
       }
     "]
     (for [reaction reactions]
       ^{:key (:id reaction)}
       [floating-emoji reaction])]))



;; >> Slide Wrapper

(defn slide-wrapper [content]
  (let [slide-id (:slide-id @state)
        slide-ids presenter/slide-ids
        idx (.indexOf slide-ids slide-id)
        total (count slide-ids)]
    [:div {:class "w-screen h-screen bg-gray-900 text-white relative"}
     ;; Main content
     [:div {:class "w-full h-full flex items-center justify-center p-8"}
      content]
     ;; Footer
     [:div {:class "absolute bottom-4 left-4 text-gray-400 text-sm"}
      (:audience-count @state) " 👥"]
     [:div {:class "absolute bottom-4 right-4 text-gray-400 text-sm"}
      (inc idx) " of " total]
     [ui/connection-pill]]))



;; >> Content Slides

(defn title-slide []
  [slide-wrapper
   [:div {:class "text-center"}
    [:h1 {:class "text-7xl font-bold mb-8"} "Wisdom of the Crowd"]
    [:p {:class "text-2xl text-gray-400"} "Join at crowd.example.com"]]])

(defn about-slide []
  [slide-wrapper
   [:div {:class "text-center"}
    [:h1 {:class "text-5xl font-bold"} "What is this talk about?"]]])

(defn wotc-slide []
  [slide-wrapper
   [:div {:class "text-center"}
    [:h1 {:class "text-5xl font-bold"} "What is Wisdom of the Crowd?"]]])

(defn wotc-answer-slide []
  [slide-wrapper
   [:div {:class "text-center space-y-8"}
    [:p {:class "text-4xl"} "Ask a crowd a question"]
    [:p {:class "text-4xl"} "get a surprisingly accurate answer back"]]])

(defn rules-slide []
  [slide-wrapper
   [:div {:class "text-center"}
    [:h1 {:class "text-5xl font-bold"} "First some ground rules"]]])

(defn independence-slide []
  [slide-wrapper
   [:div {:class "text-center"}
    [:h1 {:class "text-5xl font-bold"} "Independence"]]])

(defn diversity-slide []
  [slide-wrapper
   [:div {:class "text-center"}
    [:h1 {:class "text-5xl font-bold"} "Diversity"]]])

(defn ground-truth-slide []
  [slide-wrapper
   [:div {:class "text-center"}
    [:h1 {:class "text-5xl font-bold"} "A ground truth exists"]]])



;; >> Speaker Message Display (tweet-style)

(defn speaker-message-display []
  (let [messages (:speaker-messages @state)
        valid-messages (filter #(seq (:message %)) messages)]
    (when (seq valid-messages)
      [:div {:class "absolute top-8 right-8 max-w-md space-y-3"}
       ;; Header card
       [:div {:class "bg-gray-800 rounded-2xl p-3 shadow-xl border border-gray-700"}
        [:div {:class "flex items-center gap-3"}
         [:div {:class "w-10 h-10 rounded-full bg-gradient-to-br from-purple-500 to-pink-500 flex items-center justify-center text-lg"}
          "🌟"]
         [:div
          [:div {:class "font-bold text-white"} "Cool influencer"]
          [:div {:class "text-gray-400 text-sm"} "Live from the audience"]]]]
       ;; Messages (progressively fade: 100%, 80%, 60%, 40%, 20%)
       (for [[idx msg] (map-indexed vector valid-messages)]
         (let [opacity (- 100 (* idx 20))]
           ^{:key (:timestamp msg)}
           [:div {:class "bg-gray-800 rounded-xl p-3 shadow-lg border border-gray-700"
                  :style {:opacity (/ opacity 100)}}
            [:div {:class "text-white text-lg leading-relaxed"}
             (:message msg)]]))])))



;; >> Q3 Slide with Speaker Message

(defn q3-slide []
  (let [question (presenter/get-question "q3")
        responses (response-count "q3")
        audience (:audience-count @state)]
    [slide-wrapper
     [:div {:class "text-center"}
      [:h1 {:class "text-5xl font-bold mb-8"} (:text question)]
      [:p {:class "text-2xl text-gray-400"} responses " / " audience " Responses Received"]
      [speaker-message-display]]]))



;; >> Question Slide

(defn question-slide
  ([question-id] (question-slide question-id nil nil))
  ([question-id image] (question-slide question-id image nil))
  ([question-id image note]
   (let [question (presenter/get-question question-id)
         responses (response-count question-id)
         audience (:audience-count @state)]
     [slide-wrapper
      [:div {:class "text-center"}
       [:h1 {:class "text-5xl font-bold mb-4"} (:text question)]
       (when note
         [:p {:class "text-2xl text-gray-400 italic mb-8"} note])
       (when image
         [:img {:src image :class "max-h-64 mx-auto mb-8 rounded-lg"}])
       [:p {:class "text-2xl text-gray-400"} responses " / " audience " Responses Received"]]])))



;; >> Analysis Slide

(defn scale-chart
  ([question-id question] (scale-chart question-id question nil))
  ([question-id question answer]
   (let [stats (scale-stats question-id)
         min-val (get-in question [:options :min])
         max-val (get-in question [:options :max])
         unit (get-in question [:options :unit])
         min-label (get-in question [:options :min-label])
         max-label (get-in question [:options :max-label])
         range-size (- max-val min-val)
         bin-size (or (get-in question [:options :bin-size])
                      (js/Math.ceil (/ (inc range-size) (js/Math.min 10 (inc range-size)))))
         num-bins (js/Math.ceil (/ (inc range-size) bin-size))
         bins (vec (for [i (range num-bins)]
                  (let [bin-start (+ min-val (* i bin-size))
                        bin-end (js/Math.min max-val (+ bin-start (dec bin-size)))]
                    {:start bin-start
                     :end bin-end
                     :count (if stats
                              (reduce + (for [v (range bin-start (inc bin-end))]
                                          (get (:distribution stats) v 0)))
                              0)})))
         max-count (apply max 1 (map :count bins))]
     (if stats
       [:div {:class "text-center space-y-4"}
        [:div {:class "flex items-baseline justify-center gap-6"}
         [:div {:class "text-8xl font-bold text-blue-400"}
          (.toFixed (:average stats) 1)
          (when unit [:span {:class "text-4xl ml-2"} unit])]
         (when answer
           [:div {:class "text-4xl text-green-400"}
            "(" answer (when unit [:span {:class "text-2xl ml-1"} unit]) ")"])]
        [:div {:class "text-xl text-gray-400"}
         "Average from " (:count stats) " responses"]
        [:div {:class "flex justify-center items-end gap-1 mt-8 px-4"}
         (for [{:keys [start end count]} bins]
           (let [height (if (pos? max-count) (* 150 (/ count max-count)) 0)]
             ^{:key start}
             [:div {:class "flex-1 text-center min-w-0"}
              [:div {:class "bg-blue-500 rounded-t mx-auto"
                     :style {:height (str height "px")}}]
              [:div {:class "text-xs text-gray-400 mt-1 truncate"}
               (if (= start end) start (str start "-" end))]]))]
        (when (or min-label max-label)
          [:div {:class "flex justify-between text-sm text-gray-400 mt-2 px-4"}
           [:span (or min-label "")]
           [:span (or max-label "")]])]
       [:div {:class "text-2xl text-gray-500"} "No responses yet"]))))

(defn choice-chart [question-id question]
  (let [stats (choice-stats question-id)
        total (reduce + (vals stats))]
    (if (pos? total)
      [:div {:class "w-full max-w-2xl space-y-4"}
       (for [opt (:options question)]
         (let [cnt (get stats opt 0)
               pct (if (pos? total) (* 100 (/ cnt total)) 0)]
           ^{:key opt}
           [:div {:class "flex items-center gap-4"}
            [:div {:class "w-40 text-right text-lg"} opt]
            [:div {:class "flex-1 bg-gray-700 rounded h-10"}
             [:div {:class "bg-blue-500 h-10 rounded flex items-center justify-end pr-2"
                    :style {:width (str pct "%")}}
              (when (pos? cnt)
                [:span {:class "text-sm font-bold"} cnt])]]
            [:div {:class "w-16 text-gray-400"} (.toFixed pct 0) "%"]]))]
      [:div {:class "text-2xl text-gray-500"} "No responses yet"])))

(defn text-list [question-id]
  (let [responses (text-responses question-id)]
    (if (seq responses)
      [:div {:class "w-full max-w-2xl max-h-96 overflow-y-auto space-y-2"}
       (for [[idx resp] (map-indexed vector responses)]
         ^{:key idx}
         [:div {:class "p-3 bg-gray-700 rounded text-lg"} resp])]
      [:div {:class "text-2xl text-gray-500"} "No responses yet"])))

(defn analysis-slide
  ([question-id] (analysis-slide question-id nil))
  ([question-id answer]
   (let [question (presenter/get-question question-id)]
     [slide-wrapper
      [:div {:class "text-center w-full"}
       [:h1 {:class "text-4xl font-bold mb-8"} (:text question)]
       (case (:kind question)
         :scale [scale-chart question-id question answer]
         :choice [choice-chart question-id question]
         :text [text-list question-id]
         [:div "Unknown question type"])]])))



;; >> Trend Graph (Average over time)

(defn trend-graph [question-id answer]
  (let [data (running-averages question-id)
        question (presenter/get-question question-id)
        min-val (get-in question [:options :min])
        max-val (get-in question [:options :max])
        unit (get-in question [:options :unit])
        ;; SVG dimensions
        width 800
        height 400
        padding 60
        graph-width (- width (* 2 padding))
        graph-height (- height (* 2 padding))]
    (if (seq data)
      (let [min-time (:timestamp (first data))
            max-time (:timestamp (last data))
            time-range (max 1 (- max-time min-time))
            value-range (- max-val min-val)
            ;; Scale functions
            x-scale (fn [t] (+ padding (* graph-width (/ (- t min-time) time-range))))
            y-scale (fn [v] (- (+ padding graph-height) (* graph-height (/ (- v min-val) value-range))))
            ;; Build path
            path-points (map-indexed
                         (fn [i {:keys [timestamp average]}]
                           (let [x (x-scale timestamp)
                                 y (y-scale average)]
                             (if (zero? i)
                               (str "M " x " " y)
                               (str "L " x " " y))))
                         data)
            path-d (str/join " " path-points)
            final-avg (:average (last data))]
        [:div {:class "w-full flex flex-col items-center"}
         [:svg {:width width :height height :class "bg-gray-800 rounded-lg"}
          ;; Y-axis
          [:line {:x1 padding :y1 padding :x2 padding :y2 (+ padding graph-height)
                  :stroke "#4B5563" :stroke-width 2}]
          ;; X-axis
          [:line {:x1 padding :y1 (+ padding graph-height) :x2 (+ padding graph-width) :y2 (+ padding graph-height)
                  :stroke "#4B5563" :stroke-width 2}]
          ;; Y-axis labels
          (for [v [min-val (/ (+ min-val max-val) 2) max-val]]
            ^{:key v}
            [:text {:x (- padding 10) :y (y-scale v) :fill "#9CA3AF" :text-anchor "end" :dominant-baseline "middle" :font-size 14}
             v])
          ;; Answer line (horizontal)
          (when answer
            [:g
             [:line {:x1 padding :y1 (y-scale answer) :x2 (+ padding graph-width) :y2 (y-scale answer)
                     :stroke "#34D399" :stroke-width 2 :stroke-dasharray "8,4"}]
             [:text {:x (+ padding graph-width 10) :y (y-scale answer) :fill "#34D399" :dominant-baseline "middle" :font-size 14}
              (str answer (when unit unit))]])
          ;; Data line
          [:path {:d path-d :fill "none" :stroke "#60A5FA" :stroke-width 3}]
          ;; Data points
          (for [{:keys [timestamp average]} data]
            ^{:key timestamp}
            [:circle {:cx (x-scale timestamp) :cy (y-scale average) :r 4 :fill "#60A5FA"}])
          ;; Final average label
          [:text {:x (+ (x-scale (:timestamp (last data))) 10)
                  :y (y-scale final-avg)
                  :fill "#60A5FA" :dominant-baseline "middle" :font-size 16 :font-weight "bold"}
           (.toFixed final-avg 1)]]
         [:div {:class "mt-4 text-xl text-gray-400"}
          "Average over " (count data) " responses"]])
      [:div {:class "text-2xl text-gray-500"} "No responses yet"])))

(defn trend-slide [question-id answer]
  (let [question (presenter/get-question question-id)]
    [slide-wrapper
     [:div {:class "text-center w-full"}
      [:h1 {:class "text-4xl font-bold mb-8"} "Convergence Over Time"]
      [trend-graph question-id answer]]]))



;; >> Default Slide

(defn default-slide [slide-id]
  [slide-wrapper
   [:div {:class "text-center"}
    [:h1 {:class "text-6xl font-bold font-mono"} slide-id]]])



;; >> Slide Router

(defn render-slide [slide-id]
  (case slide-id
    "title" [title-slide]
    "about" [about-slide]
    "wotc" [wotc-slide]
    "q1" [question-slide "q1"]
    "q1-results" [analysis-slide "q1"]
    "wotc-answer" [wotc-answer-slide]
    "rules" [rules-slide]
    "q2" [question-slide "q2" persimmon-img]
    "q2-results" [analysis-slide "q2" 221]
    "independence" [independence-slide]
    "q3" [q3-slide]
    "q3-results" [analysis-slide "q3" 1953]
    "q3-trend" [trend-slide "q3" 1953]
    "diversity" [diversity-slide]
    "q4" [question-slide "q4"]
    "q4-results" [analysis-slide "q4" 73]
    "q4-trend" [trend-slide "q4" 73]
    "ground-truth" [ground-truth-slide]
    "q5" [question-slide "q5"]
    "q5-results" [analysis-slide "q5"]
    "q5-trend" [trend-slide "q5" nil]
    [default-slide slide-id]))

(defn display-ui []
  (let [slide-id (:slide-id @state)]
    [:div {:class "relative"}
     (if slide-id
       [render-slide slide-id]
       [slide-wrapper
        [:div {:class "text-2xl text-gray-500"} "Waiting for presenter..."]])
     [reactions-layer]]))



;; >> Init

(defn init! []

  ;; Re-render when ably connection status changes
  (add-watch ably/state ::connection
    (fn [_ _ _ _]
      (swap! state identity)))

  ;; Watch audience count
  (ably/on-presence-change! "audience"
    (fn []
      (ably/get-presence-members "audience"
        (fn [members]
          (swap! state assoc :audience-count (count members))))))

  ;; Watch presenter state
  (presenter/on-state-change!
    (fn []
      (presenter/get-state
        (fn [presenter-state]
          (when presenter-state
            (swap! state assoc :slide-id (:slide-id presenter-state)))))))

  ;; Subscribe to votes
  (ably/subscribe! "votes" process-vote)

  ;; Subscribe to speaker messages
  (ably/subscribe! "speaker" process-speaker-message)

  ;; Subscribe to reactions
  (ably/subscribe! "reactions" process-reaction)

  ;; Subscribe to control channel for reset
  (ably/subscribe! "control" (fn [_] (reset-state!)))

  ;; Cleanup old reactions periodically (just to free memory)
  (js/setInterval cleanup-old-reactions! 5000))
