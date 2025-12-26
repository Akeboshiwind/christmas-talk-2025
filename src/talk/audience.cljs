(ns talk.audience
  (:require ["./ably.js" :as ably]
            ["./presenter.js" :as presenter]
            ["./ui.js" :as ui]))

;; >> State

(def default-state {:slide-id nil
                    :my-votes {}      ;; {question-id -> vote-value}
                    :my-name nil      ;; Player's display name
                    :name-submitted? false})

(defn save-state! []
  (js/localStorage.setItem "audience-state"
    (js/JSON.stringify (select-keys @state [:my-votes :my-name :name-submitted?]))))

(defn load-state []
  (when-let [saved (js/localStorage.getItem "audience-state")]
    (js/JSON.parse saved)))

(def state (atom (merge default-state (load-state))))

(def CHANNEL "audience")

(defn reset-state! []
  (js/localStorage.removeItem "audience-state")
  (reset! state default-state)
  ;; Update presence to clear name
  (ably/update-presence! CHANNEL {:name nil})
  ;; Re-fetch presenter state
  (presenter/get-state
    (fn [presenter-state]
      (when presenter-state
        (swap! state assoc :slide-id (:slide-id presenter-state))))))

(defn my-vote-for [question-id]
  (get (:my-votes @state) question-id))



;; >> Utilities

(defn debounce [f delay-ms]
  (let [timeout (atom nil)]
    (fn [& args]
      (when-let [t @timeout]
        (js/clearTimeout t))
      (reset! timeout
        (js/setTimeout #(apply f args) delay-ms)))))

;; Fallback UUID generator for non-HTTPS contexts
(defn generate-id []
  (str (js/Date.now) "-" (js/Math.floor (* (js/Math.random) 1000000))))



;; >> Name Management

(defn update-presence-name! []
  (ably/update-presence! CHANNEL {:name (:my-name @state)}))

(defn submit-name! [name]
  (swap! state assoc :my-name name :name-submitted? true)
  (save-state!)
  (update-presence-name!))



;; >> Voting

(def publish-vote!
  (debounce
    (fn [question-id value]
      (ably/publish! "votes" "vote" {:client-id ably/client-id
                                      :question-id question-id
                                      :value value
                                      :timestamp (js/Date.now)}))
    100))

(defn submit-vote! [question-id value]
  ;; Update local state immediately for responsiveness
  (swap! state assoc-in [:my-votes question-id] value)
  (save-state!)
  ;; Debounced publish to Ably
  (publish-vote! question-id value))



;; >> UI Components

(defn button [attrs & children]
  (into [:button (merge {:class "px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 active:bg-blue-800 disabled:bg-gray-600"
                         :disabled (not (ably/connected?))}
                        attrs)]
        children))

(defn scale-voter [question-id question]
  (let [current-vote (my-vote-for question-id)
        {:keys [min max unit min-label max-label]} (:options question)
        value (or current-vote min)
        on-value-change (fn [v]
                          (let [clamped (js/Math.min max (js/Math.max min v))]
                            (submit-vote! question-id clamped)))]
    [:div {:class "space-y-4"}
     [:h2 {:class "text-xl font-semibold text-center"} (:text question)]
     ;; Value input with optional unit
     [:div {:class "flex items-center justify-center gap-2"}
      [:input {:type "number"
               :min min
               :max max
               :value value
               :class "w-full text-4xl font-bold text-center border-b-2 border-gray-600 focus:border-blue-500 outline-none bg-transparent"
               :on-change #(let [v (js/parseInt (.. % -target -value))]
                             (when-not (js/isNaN v)
                               (on-value-change v)))}]
      (when unit
        [:span {:class "text-xl text-gray-400"} unit])]
     ;; Slider
     [:div {:class "space-y-1"}
      [:input {:type "range"
               :min min
               :max max
               :value value
               :class "w-full h-3 bg-gray-700 rounded-lg appearance-none cursor-pointer"
               :on-change #(on-value-change (js/parseInt (.. % -target -value)))}]
      ;; Min/max labels
      [:div {:class "flex justify-between text-sm text-gray-400"}
       [:span (or min-label min)]
       [:span (or max-label max)]]]
     (when current-vote
       [:div {:class "text-sm text-green-400 text-center"} "Submitted!"])]))

(defn choice-voter [question-id question]
  (let [current-vote (my-vote-for question-id)]
    [:div {:class "space-y-4"}
     [:h2 {:class "text-xl font-semibold text-center"} (:text question)]
     [:div {:class "flex flex-col gap-2"}
      (for [opt (:options question)]
        ^{:key opt}
        [button {:class (if (= opt current-vote)
                          "px-4 py-2 bg-green-600 text-white rounded-lg"
                          "px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700")
                 :on-click #(submit-vote! question-id opt)}
         opt])]
     (when current-vote
       [:div {:class "text-sm text-green-400 text-center"} "Submitted!"])]))

(defn question-voter [question-id]
  (let [question (presenter/get-question question-id)]
    (case (:kind question)
      :scale [scale-voter question-id question]
      :choice [choice-voter question-id question]
      [:div "Unknown question type"])))

(defn name-input-ui []
  (let [input-id "name-input"]
    [:div {:class "text-center space-y-6"}
     [:h2 {:class "text-2xl font-bold"} "Welcome to the Quiz!"]
     [:p {:class "text-gray-400"} "Enter your name to join"]
     [:input {:id input-id
              :type "text"
              :class "w-full px-4 py-3 bg-gray-700 border border-gray-600 rounded-lg text-white text-lg text-center"
              :placeholder "Your name..."
              :default-value (or (:my-name @state) "")}]
     [button {:class "w-full px-4 py-3 bg-green-600 text-white rounded-lg hover:bg-green-700 text-lg font-semibold"
              :on-click (fn []
                          (let [input (js/document.getElementById input-id)
                                value (.trim (.-value input))]
                            (when (seq value)
                              (submit-name! value))))}
      "Join Quiz"]]))

(def reaction-emojis ["❤️" "👍" "😮" "🧠" "🍆"])
(def REACTIONS-CHANNEL "reactions")

(def send-reaction!
  (debounce
    (fn [emoji]
      (ably/publish! REACTIONS-CHANNEL "reaction" {:emoji emoji
                                                    :id (generate-id)
                                                    :timestamp (js/Date.now)}))
    200))

(defn emoji-button [emoji]
  [:button {:class "text-4xl p-3 hover:scale-125 transition-transform active:scale-90"
            :on-click #(send-reaction! emoji)}
   emoji])

(defn waiting-ui []
  [:div {:class "text-center text-gray-400 space-y-8"}
   [:div
    [:h2 {:class "text-xl"} "Waiting for next question..."]
    [:p "The presenter will activate a question soon."]]
   [:div {:class "flex justify-center gap-4"}
    (for [emoji reaction-emojis]
      ^{:key emoji}
      [emoji-button emoji])]])



;; >> Voter Router

(defn render-voter [slide-id]
  (if (presenter/question-id? slide-id)
    [question-voter slide-id]
    [waiting-ui]))

(defn audience-ui []
  (let [slide-id (:slide-id @state)
        has-name? (:name-submitted? @state)]
    [:div {:class "min-h-screen bg-gray-900 text-white"}
     [:div {:class "p-4 max-w-md mx-auto"}
      (if has-name?
        [:div
         [:div {:class "flex justify-between items-center mb-4"}
          [:h1 {:class "text-2xl font-bold"} "Quiz"]
          [:span {:class "text-gray-400"} (:my-name @state)]]
         [render-voter slide-id]]
        [name-input-ui])
      [ui/connection-pill]]]))



;; >> Init

(defn init! []

  ;; Re-render when ably connection status changes
  (add-watch ably/state ::connection
    (fn [_ _ _ _]
      (swap! state identity)))

  ;; Enter audience presence with name if we have one
  (ably/enter-presence! CHANNEL {:name (:my-name @state)})

  ;; Watch presenter for current slide
  (presenter/on-state-change!
    (fn []
      (presenter/get-state
        (fn [presenter-state]
          (when presenter-state
            (swap! state assoc :slide-id (:slide-id presenter-state)))))))

  ;; Subscribe to control channel for reset
  (ably/subscribe! "control" (fn [_] (reset-state!))))
