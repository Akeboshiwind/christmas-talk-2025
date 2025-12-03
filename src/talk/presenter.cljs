(ns talk.presenter
  (:require ["./ably.js" :as ably]
            ["./ui.js" :as ui]))

;; >> Slides & Questions

(def questions
  {"q1" {:id "q1"
         :text "How familiar are you with Clojure?"
         :kind :scale
         :options {:min 1 :max 10}}
   "q2" {:id "q2"
         :text "What's your favorite programming paradigm?"
         :kind :choice
         :options ["Functional" "Object-Oriented" "Procedural" "Other"]}
   "q3" {:id "q3"
         :text "What feature would you like to see next?"
         :kind :text}})

(def slide-ids
  ["title" "q1" "q1-results" "about" "q2" "q2-results" "q3" "q3-results" "outro"])

(defn get-question [question-id]
  (get questions question-id))


;; >> State

(def default-state {:slide-id (first slide-ids)
                    :audience-count 0})

(defn save-state! []
  (js/localStorage.setItem "presenter-state"
    (js/JSON.stringify (select-keys @state [:slide-id]))))

(defn load-state []
  (when-let [saved (js/localStorage.getItem "presenter-state")]
    (let [parsed (js/JSON.parse saved)]
      {:slide-id (or (:slide-id parsed) (first slide-ids))})))

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
  (ably/update-presence! CHANNEL {:slide-id (:slide-id @state)}))



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
  (swap! state assoc :slide-id (first slide-ids))
  (sync-state!))



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

(defn presenter-ui []
  (let [disabled? (not (ably/connected?))
        current-slide (:slide-id @state)
        idx (current-slide-index)]
    [:div {:class "p-4 space-y-6 max-w-md mx-auto"}
     [:h1 {:class "text-2xl font-bold"} "Presenter Controls"]

     [:div {:class "text-lg"}
      "Audience connected: " (:audience-count @state)]

     [:div {:class "space-y-2"}
      [:h2 {:class "text-xl font-semibold"} "Current Slide"]
      [:div {:class "flex gap-2 items-center"}
       [button {:on-click prev-slide! :disabled disabled?} "Prev"]
       [:span {:class "px-4 py-2 font-mono text-lg"} current-slide]
       [:span {:class "text-gray-500"} "(" (inc idx) "/" (count slide-ids) ")"]
       [button {:on-click next-slide! :disabled disabled?} "Next"]]]

     [:div {:class "space-y-2"}
      [:h2 {:class "text-xl font-semibold"} "All Slides"]
      [:div {:class "flex flex-wrap gap-2"}
       (for [sid slide-ids]
         ^{:key sid}
         [slide-button sid disabled?])]]

     [:div {:class "pt-4 border-t"}
      [button {:class "px-4 py-2 bg-red-600 text-white rounded-lg hover:bg-red-700 disabled:bg-gray-400"
               :on-click reset-state!
               :disabled disabled?}
       "Reset"]]

     [ui/connection-pill]]))



;; >> Init

(defn init! []

  ;; Re-render when ably connection status changes
  (add-watch ably/state ::connection
    (fn [_ _ _ _]
      (swap! state identity)))

  ;; Enter presenter presence with initial state
  (ably/enter-presence! CHANNEL {:slide-id (:slide-id @state)})

  ;; Watch audience count
  (ably/on-presence-change! "audience"
    (fn []
      (ably/get-presence-members "audience"
        (fn [members]
          (swap! state assoc :audience-count (count members)))))))
