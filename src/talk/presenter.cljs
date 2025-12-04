(ns talk.presenter
  (:require ["./ably.js" :as ably]
            ["./ui.js" :as ui]))

;; >> Slides & Questions

(def slides
  ["title"
   "about"
   ["wotc" "q1" "q1-results" "wotc-answer"]
   ["rules" "q2" "q2-results"]
   ["independence" "q3" "q3-results" "q3-trend"]
   ["diversity" "q4" "q4-results" "q4-trend"]
   ["ground-truth" "q5" "q5-results" "q5-trend"]
   ["point" "search-engines" "llms" "but-not-really"]
   ["the-end" "tech-stack"]])

(def slide-ids (->> slides
                    (map #(if (string? %) [%] %))
                    (apply concat)
                    vec))

(def questions
  {"q1" {:id "q1"
         :text "Who has heard of \"Wisdom of the Crowd\" before?"
         :kind :choice
         :options ["Yes" "No" "Maybe"]}
   "q2" {:id "q2"
         :text "How heavy is this Persimmon (in grams)?"
         :kind :scale
         :options {:min 1 :max 500 :unit "g" :bin-size 50}}
   "q3" {:id "q3"
         :text "What year did the first person reach the peak of Mount Everest?"
         :kind :scale
         :options {:min 1800 :max 2000 :bin-size 10}}
   "q4" {:id "q4"
         :text "What percentage of the world population has internet access?"
         :kind :scale
         :options {:min 0 :max 100 :unit "%" :bin-size 10}}
   "q5" {:id "q5"
         :text "How ethical is it to train LLMs on public code on GitHub?"
         :kind :scale
         :options {:min 0 :max 10 :bin-size 1
                   :min-label "Not ethical" :max-label "Very ethical"}}})

(def notes
  {"about" ["This is my backup idea..."
            "So let's just have fun"]
   "rules" ["1. No peeking!"
            "2. Feel free to change your answers as much as you like"
            "3. That's it"]
   "independence" ["For errors to cancel out, the errors need to be unbiased"]
   "diversity" ["Diversity in errors helps reduce overall error"]
   "ground-truth" ["There needs to obviously be a precise answer to the question"]
   "point" ["Originally I had a point to this talk"
            "Because this sounds very similar to"]
   "search-engines" ["I mean..."]
   "llms" ["That's what I thought"
           "But thinking further"]
   "but-not-really" ["Not independent, even with temperature"
                     "They're fairly diverse"
                     "They seem to give reasonable answers without ground truth"]})

(defn get-question [question-id]
  (get questions question-id))


;; >> State

(def default-state {:slide-id (first slide-ids)
                    :audience-count 0
                    :selected-speaker nil
                    :audience-members []})

(defn save-state! []
  (js/localStorage.setItem "presenter-state"
    (js/JSON.stringify (select-keys @state [:slide-id :selected-speaker]))))

(defn load-state []
  (when-let [saved (js/localStorage.getItem "presenter-state")]
    (let [parsed (js/JSON.parse saved)]
      {:slide-id (or (:slide-id parsed) (first slide-ids))
       :selected-speaker (:selected-speaker parsed)})))

(def state (atom (merge default-state (load-state))))

(def CHANNEL "presenter")



;; >> Presence Helpers

(defn get-state [callback]
  (ably/get-presence-members CHANNEL
    (fn [members]
      (callback (when (seq members) (.-data (first members)))))))

(defn on-state-change! [callback]
  (ably/on-presence-change! CHANNEL callback))

;; >> Sync state to presenter channel

(defn sync-state! []
  (save-state!)
  (ably/update-presence! CHANNEL {:slide-id (:slide-id @state)
                                   :selected-speaker (:selected-speaker @state)}))



;; >> Speaker Selection

(defn select-random-speaker! []
  (let [members (:audience-members @state)]
    (when (seq members)
      (let [speaker (rand-nth members)]
        (swap! state assoc :selected-speaker speaker)
        (sync-state!)))))

(defn clear-speaker-messages! []
  (ably/publish! "speaker" "message" {:command "clear"}))

(defn speaker-present? []
  (let [speaker (:selected-speaker @state)
        members (:audience-members @state)]
    (and speaker (some #(= % speaker) members))))



;; >> Control Actions

(defn current-slide-index []
  (let [current (:slide-id @state)]
    (or (.indexOf slide-ids current) 0)))

(defn next-slide! []
  (let [idx (current-slide-index)
        next-idx (min (dec (count slide-ids)) (inc idx))]
    (swap! state assoc :slide-id (nth slide-ids next-idx))
    (sync-state!)))

(defn prev-slide! []
  (let [idx (current-slide-index)
        prev-idx (max 0 (dec idx))]
    (swap! state assoc :slide-id (nth slide-ids prev-idx))
    (sync-state!)))

(defn go-to-slide! [slide-id]
  (swap! state assoc :slide-id slide-id)
  (sync-state!))

(defn reset-state! []
  (js/localStorage.removeItem "presenter-state")
  (swap! state assoc :slide-id (first slide-ids) :selected-speaker nil)
  (sync-state!)
  ;; Broadcast reset to all clients
  (ably/publish! "control" "reset" {:timestamp (js/Date.now)}))



;; >> UI Components

(defn button [attrs & children]
  (into [:button (merge {:class "px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 active:bg-blue-800 disabled:bg-gray-400"}
                        attrs)]
        children))

(defn slide-button [slide-id disabled?]
  (let [current? (= slide-id (:slide-id @state))]
    [button {:class (if current?
                      "px-4 py-2 bg-green-600 text-white rounded-lg disabled:bg-gray-400"
                      "px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:bg-gray-400")
             :on-click #(go-to-slide! slide-id)
             :disabled disabled?}
     slide-id]))

(defn speaker-selection-ui [disabled?]
  (let [speaker (:selected-speaker @state)
        present? (speaker-present?)]
    [:div {:class "p-3 bg-purple-900/50 border border-purple-700 rounded-lg space-y-2"}
     [:h2 {:class "text-sm font-semibold text-purple-400"} "Selected Speaker"]
     (if speaker
       [:div {:class "flex items-center gap-2"}
        [:span {:class (if present? "text-green-400" "text-red-400")} "●"]
        [:span {:class "font-mono text-sm truncate flex-1"} speaker]
        [:span {:class "text-xs text-gray-400"} (if present? "online" "offline")]]
       [:div {:class "text-gray-400 text-sm"} "No speaker selected"])
     [:div {:class "flex gap-2"}
      [button {:on-click select-random-speaker!
               :disabled disabled?
               :class "px-3 py-1 bg-purple-600 text-white text-sm rounded-lg hover:bg-purple-700 disabled:bg-gray-600"}
       (if speaker "Re-roll" "Select Random")]
      [button {:on-click clear-speaker-messages!
               :disabled disabled?
               :class "px-3 py-1 bg-gray-600 text-white text-sm rounded-lg hover:bg-gray-500 disabled:bg-gray-700"}
       "Clear Messages"]]]))

(defn presenter-ui []
  (let [disabled? (not (ably/connected?))
        current-slide (:slide-id @state)
        idx (current-slide-index)
        on-q3? (= current-slide "q3")]
    ;; Auto-select speaker when entering q3 if none selected
    (when (and on-q3? (not (:selected-speaker @state)) (seq (:audience-members @state)))
      (select-random-speaker!))
    [:div {:class "min-h-screen bg-gray-900 text-white"}
     [:div {:class "p-4 space-y-6 max-w-md mx-auto"}
      [:h1 {:class "text-2xl font-bold"} "Presenter Controls"]

      [:div {:class "text-lg"}
       "Audience connected: " (:audience-count @state)]

      [:div {:class "space-y-2"}
       [:h2 {:class "text-xl font-semibold"} "Current Slide"]
       [:div {:class "flex gap-2 items-center"}
        [button {:on-click prev-slide! :disabled disabled?} "Prev"]
        [button {:on-click next-slide! :disabled disabled?} "Next"]
        [:span {:class "px-4 py-2 font-mono text-lg"} current-slide]
        [:span {:class "text-gray-400"} "(" (inc idx) "/" (count slide-ids) ")"]]]

      (when on-q3?
        [speaker-selection-ui disabled?])

      (when-let [slide-notes (get notes current-slide)]
        [:div {:class "p-3 bg-yellow-900/50 border border-yellow-700 rounded-lg"}
         [:h2 {:class "text-sm font-semibold text-yellow-400 mb-2"} "Notes"]
         [:div {:class "space-y-1"}
          (for [[idx note] (map-indexed vector slide-notes)]
            ^{:key idx}
            [:p {:class "text-yellow-200"} note])]])

      [:div {:class "space-y-2"}
       [:h2 {:class "text-xl font-semibold"} "All Slides"]
       [:div {:class "flex flex-col gap-2"}
        (for [[idx item] (map-indexed vector slides)]
          ^{:key idx}
          (if (string? item)
            [slide-button item disabled?]
            [:div {:class "flex gap-2"}
             (for [sid item]
               ^{:key sid}
               [slide-button sid disabled?])]))]]

      [:div {:class "pt-4 border-t border-gray-700"}
       [button {:class "px-4 py-2 bg-red-600 text-white rounded-lg hover:bg-red-700 disabled:bg-gray-600"
                :on-click #(when (js/confirm "Reset all state for presenter, display, and audience?")
                             (reset-state!))
                :disabled disabled?}
        "Reset"]]

      [ui/connection-pill]]]))



;; >> Init

(defn init! []

  ;; Re-render when ably connection status changes
  (add-watch ably/state ::connection
    (fn [_ _ _ _]
      (swap! state identity)))

  ;; Enter presenter presence with initial state
  (ably/enter-presence! CHANNEL {:slide-id (:slide-id @state)})

  ;; Watch audience members
  (ably/on-presence-change! "audience"
    (fn []
      (ably/get-presence-members "audience"
        (fn [members]
          (let [member-ids (mapv #(.-clientId %) members)]
            (swap! state assoc
                   :audience-count (count members)
                   :audience-members member-ids)))))))
