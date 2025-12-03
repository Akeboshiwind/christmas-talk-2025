(ns talk.display
  (:require ["./ably.js" :as ably]
            ["./presenter.js" :as presenter]
            ["./ui.js" :as ui]))


;; >> State

(def state (atom {:slide-id nil
                  :votes {}  ;; {question-id -> {:latest-vote {client-id -> vote}, :all-votes [vote]}}
                  :audience-count 0}))



;; >> Vote Processing

(defn process-vote [vote]
  (let [client-id (:client-id vote)
        question-id (:question-id vote)]
    (swap! state (fn [s]
                   (-> s
                       (assoc-in [:votes question-id :latest-vote client-id] vote)
                       (update-in [:votes question-id :all-votes] (fnil conj []) vote))))))



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



;; >> Title Slide

(defn title-slide []
  [slide-wrapper
   [:div {:class "text-center"}
    [:h1 {:class "text-7xl font-bold mb-8"} "Wisdom of the Crowd"]
    [:p {:class "text-2xl text-gray-400"} "Connect using https://crowd.example.com"]]])

(defn outro-slide []
  [slide-wrapper
   [:div {:class "text-center"}
    [:h1 {:class "text-7xl font-bold mb-8"} "Thank You!"]
    [:p {:class "text-2xl text-gray-400"} "Questions?"]]])



;; >> Question Slide

(defn question-slide [question-id]
  (let [question (presenter/get-question question-id)
        responses (response-count question-id)
        audience (:audience-count @state)]
    [slide-wrapper
     [:div {:class "text-center"}
      [:h1 {:class "text-5xl font-bold mb-8"} (:text question)]
      [:p {:class "text-2xl text-gray-400"} responses " / " audience " Responses Received"]]]))



;; >> Analysis Slide

(defn scale-chart [question-id question]
  (let [stats (scale-stats question-id)]
    (if stats
      [:div {:class "text-center space-y-4"}
       [:div {:class "text-8xl font-bold text-blue-400"}
        (.toFixed (:average stats) 1)]
       [:div {:class "text-xl text-gray-400"}
        "Average from " (:count stats) " responses"]
       [:div {:class "flex justify-center gap-2 mt-8"}
        (for [n (range (get-in question [:options :min])
                       (inc (get-in question [:options :max])))]
          (let [cnt (get (:distribution stats) n 0)]
            ^{:key n}
            [:div {:class "text-center"}
             [:div {:class "bg-blue-500 w-12 rounded-t"
                    :style {:height (str (* cnt 30) "px")}}]
             [:div {:class "text-sm text-gray-400 mt-1"} n]]))]]
      [:div {:class "text-2xl text-gray-500"} "No responses yet"])))

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

(defn analysis-slide [question-id]
  (let [question (presenter/get-question question-id)]
    [slide-wrapper
     [:div {:class "text-center w-full"}
      [:h1 {:class "text-4xl font-bold mb-8"} (:text question)]
      (case (:kind question)
        :scale [scale-chart question-id question]
        :choice [choice-chart question-id question]
        :text [text-list question-id]
        [:div "Unknown question type"])]]))



;; >> Content Slide

(defn content-slide [title]
  [slide-wrapper
   [:div {:class "text-center"}
    [:h1 {:class "text-5xl font-bold"} title]]])



;; >> Default Slide

(defn default-slide [slide-id]
  [slide-wrapper
   [:div {:class "text-center"}
    [:h1 {:class "text-6xl font-bold font-mono"} slide-id]]])



;; >> Slide Router

(defn render-slide [slide-id]
  (case slide-id
    "title" [title-slide]
    "outro" [outro-slide]
    "q1" [question-slide "q1"]
    "q2" [question-slide "q2"]
    "q3" [question-slide "q3"]
    "q1-results" [analysis-slide "q1"]
    "q2-results" [analysis-slide "q2"]
    "q3-results" [analysis-slide "q3"]
    "about" [content-slide "About Clojure"]
    [default-slide slide-id]))

(defn display-ui []
  (let [slide-id (:slide-id @state)]
    (if slide-id
      [render-slide slide-id]
      [slide-wrapper
       [:div {:class "text-2xl text-gray-500"} "Waiting for presenter..."]])))



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
  (ably/subscribe! "votes" process-vote))
